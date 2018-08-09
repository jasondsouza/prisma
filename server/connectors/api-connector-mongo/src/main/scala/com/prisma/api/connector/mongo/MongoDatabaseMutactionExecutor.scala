package com.prisma.api.connector.mongo

import com.prisma.api.connector._
import com.prisma.api.connector.mongo.database._
import com.prisma.api.connector.mongo.impl.{CreateNodeInterpreter, DeleteNodeInterpreter, ResetDataInterpreter, UpdateNodeInterpreter}
import com.prisma.gc_values.{CuidGCValue, IdGCValue}
import org.mongodb.scala.{MongoClient, MongoDatabase}

import scala.concurrent.{ExecutionContext, Future}

class MongoDatabaseMutactionExecutor(client: MongoClient)(implicit ec: ExecutionContext) extends DatabaseMutactionExecutor {

  override def executeTransactionally(mutaction: TopLevelDatabaseMutaction): Future[MutactionResults] = execute(mutaction, transactionally = true)

  override def executeNonTransactionally(mutaction: TopLevelDatabaseMutaction): Future[MutactionResults] = execute(mutaction, transactionally = false)

  private def execute(mutaction: TopLevelDatabaseMutaction, transactionally: Boolean): Future[MutactionResults] = {
    val actionsBuilder = MongoActionsBuilder(mutaction.project.id, client)
    val action         = generateTopLevelMutaction(actionsBuilder.database, mutaction, actionsBuilder)

    run(actionsBuilder.database, action)
  }

  def generateTopLevelMutaction(
      database: MongoDatabase,
      mutaction: TopLevelDatabaseMutaction,
      mutationBuilder: MongoActionsBuilder
  ): MongoAction[MutactionResults] = {
    mutaction match {
      case m: FurtherNestedMutaction =>
        for {
          result <- interpreterFor(m).mongoAction(mutationBuilder)
          childResults <- result match {
                           case result: FurtherNestedMutactionResult =>
                             val x = m.allNestedMutactions.map(x => generateNestedMutaction(database, x, result.id, mutationBuilder))
                             MongoAction.seq(x)
                           case _ => MongoAction.successful(Vector.empty)
                         }
        } yield MutactionResults(result, childResults)

      case m: FinalMutaction =>
        for {
          result <- interpreterFor(m).mongoAction(mutationBuilder)
        } yield MutactionResults(result, Vector.empty)

      case _ => sys.error("not implemented yet")
    }
  }

  def generateNestedMutaction(database: MongoDatabase,
                              mutaction: NestedDatabaseMutaction,
                              parentId: IdGCValue,
                              mutationBuilder: MongoActionsBuilder): MongoAction[MutactionResults] = {
    mutaction match {
      case m: FurtherNestedMutaction =>
        for {
          result <- interpreterFor(m).mongoAction(mutationBuilder, parentId)
          childResults <- result match {
                           case result: FurtherNestedMutactionResult =>
                             val x = m.allNestedMutactions.map(x => generateNestedMutaction(database, x, result.id, mutationBuilder))
                             MongoAction.seq(x)
                           case _ => MongoAction.successful(Vector.empty)
                         }
        } yield MutactionResults(result, childResults)

      case m: FinalMutaction =>
        for {
          result <- interpreterFor(m).mongoAction(mutationBuilder, parentId)
        } yield MutactionResults(result, Vector.empty)

      case _ => sys.error("not implemented yet")
    }
  }

  def interpreterFor(mutaction: TopLevelDatabaseMutaction): TopLevelDatabaseMutactionInterpreter = mutaction match {
    case m: TopLevelCreateNode => CreateNodeInterpreter(mutaction = m, includeRelayRow = false)
    case m: TopLevelUpdateNode => UpdateNodeInterpreter(mutaction = m)
    case m: TopLevelUpsertNode => ???
    case m: TopLevelDeleteNode => DeleteNodeInterpreter(mutaction = m)
    case m: UpdateNodes        => ???
    case m: DeleteNodes        => ???
    case m: ResetData          => ResetDataInterpreter(mutaction = m)
    case m: ImportNodes        => ???
    case m: ImportRelations    => ???
    case m: ImportScalarLists  => ???
  }

  def interpreterFor(mutaction: NestedDatabaseMutaction): NestedDatabaseMutactionInterpreter = mutaction match {
    case m: NestedCreateNode => ???
    case m: NestedUpdateNode => ???
    case m: NestedUpsertNode => ???
    case m: NestedDeleteNode => ???
    case m: NestedConnect    => ???
    case m: NestedDisconnect => ???
  }

//Slick replacement ideas

  def run[A](database: MongoDatabase, action: MongoAction[A]): Future[A] = {
    action match {
      case SuccessAction(value) =>
        Future.successful(value)

      case SimpleMongoAction(fn) =>
        fn(database)

      case FlatMapAction(source, fn) =>
        for {
          result     <- run(database, source)
          nextResult <- run(database, fn(result))
        } yield nextResult

      case MapAction(source, fn) =>
        for {
          result <- run(database, source)
        } yield fn(result)

      case SequenceAction(actions) =>
        sequence(database, actions)

    }
  }

  def sequence[A](database: MongoDatabase, actions: Vector[MongoAction[A]]): Future[Vector[A]] = {
    if (actions.isEmpty) {
      Future.successful(Vector.empty)
    } else {
      for {
        headResult  <- run(database, actions.head)
        nextResults <- sequence(database, actions.tail)
      } yield headResult +: nextResults
    }
  }
}