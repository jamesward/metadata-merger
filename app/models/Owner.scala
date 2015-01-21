package models

import java.util.UUID

import scalikejdbc.WrappedResultSet
import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class Owner(id: String)

object Owner extends SQLSyntaxSupport[Owner] {

  override val columnNames = Seq("id")

  lazy val su = Owner.syntax

  def db(o: SyntaxProvider[Owner])(rs: WrappedResultSet): Owner = db(o.resultName)(rs)

  def db(o: ResultName[Owner])(rs: WrappedResultSet): Owner = Owner(rs.string(o.id))

  def create(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Owner] = {
    val id = UUID.randomUUID.toString
    withSQL(insert.into(Owner).namedValues(column.id -> id)).update().map(_ => Owner(id))
  }

}