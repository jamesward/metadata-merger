package models

import java.util.UUID

import play.api.libs.Crypto
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scalikejdbc.WrappedResultSet
import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class SalesforceUser(userId: String, ownerId: String)

object SalesforceUser extends SQLSyntaxSupport[SalesforceUser] {

  override val columnNames = Seq("user_id", "owner_id")

  lazy val su = SalesforceUser.syntax

  def db(su: SyntaxProvider[SalesforceUser])(rs: WrappedResultSet): SalesforceUser = db(su.resultName)(rs)

  def db(su: ResultName[SalesforceUser])(rs: WrappedResultSet): SalesforceUser = {
    SalesforceUser(rs.string(su.userId), rs.string(su.ownerId))
  }

  def find(userId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[SalesforceUser] = {
    withSQL(
      select
        .from[SalesforceUser](SalesforceUser as su)
        .where.eq(su.userId, userId)
    ).map(SalesforceUser.db(su)).single().future().flatMap {
      _.fold(Future.failed[SalesforceUser](SalesforceUserNotFound()))(Future.successful)
    }
  }

  def create(userId: String, ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[SalesforceUser] = {
    withSQL(
      insert.into(SalesforceUser).namedValues(column.userId -> userId, column.ownerId -> ownerId)
    ).update().map(_ => SalesforceUser(userId, ownerId))
  }

}

case class SalesforceUserNotFound() extends Exception {
  override def getMessage = "Salesforce User not found"
}