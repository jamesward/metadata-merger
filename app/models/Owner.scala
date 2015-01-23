package models

import java.util.UUID

import play.api.libs.Crypto
import scalikejdbc.WrappedResultSet
import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class Owner(id: String, githubAccessToken: Option[String] = None) {

  private val o = Owner.column

  def updateGithubAccessToken(githubAccessToken: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Owner] = {
    withSQL(
      update(Owner)
        .set(o.githubAccessToken -> Crypto.encryptAES(githubAccessToken))
        .where.eq(o.id, id)
    ).update().future().map(_ => copy(githubAccessToken = Some(githubAccessToken)))
  }

}

object Owner extends SQLSyntaxSupport[Owner] {

  override val columnNames = Seq("id", "github_access_token")

  lazy val o = Owner.syntax

  def db(o: SyntaxProvider[Owner])(rs: WrappedResultSet): Owner = db(o.resultName)(rs)

  def db(o: ResultName[Owner])(rs: WrappedResultSet): Owner = {
    Owner(rs.string(o.id), rs.stringOpt(o.githubAccessToken).map(Crypto.decryptAES))
  }

  def create(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Owner] = {
    val id = UUID.randomUUID.toString
    withSQL(insert.into(Owner).namedValues(column.id -> id)).update().map(_ => Owner(id))
  }

  def find(id: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Owner] = {
    withSQL(
      select
        .from[Owner](Owner as o)
        .where.eq(o.id, id)
    ).map(Owner.db(o)).single().future().flatMap { maybeOwner =>
      maybeOwner.fold(Future.failed[Owner](OwnerNotFound()))(Future.successful)
    }
  }

}

case class OwnerNotFound() extends Exception {
  override def getMessage = "Owner not found"
}