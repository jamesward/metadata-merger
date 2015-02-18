package models

import play.api.libs.Crypto
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scalikejdbc.WrappedResultSet
import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class Repo(id: Long, githubId: Long, name: String, fullName: String, description: String, htmlUrl: String, ownerId: String) {

  private val r = Repo.column

}

object Repo extends SQLSyntaxSupport[Repo] {

  implicit val jsonWrites: Writes[Repo] = (
    (JsPath \ "id").write[Long] ~
    (JsPath \ "name").write[String] ~
    (JsPath \ "full_name").write[String] ~
    (JsPath \ "description").write[String] ~
    (JsPath \ "html_url").write[String]
  )(repo => (repo.id, repo.name, repo.fullName, repo.description, repo.htmlUrl))

  override val columnNames = Seq("id", "github_id", "name", "full_name", "description", "html_url", "owner_id")

  lazy val r = Repo.syntax

  def db(r: SyntaxProvider[Repo])(rs: WrappedResultSet): Repo = db(r.resultName)(rs)

  def db(r: ResultName[Repo])(rs: WrappedResultSet): Repo = {
    Repo(
      rs.long(r.id),
      rs.long(r.githubId),
      rs.string(r.name),
      rs.string(r.fullName),
      rs.string(r.description),
      rs.string(r.htmlUrl),
      rs.string(r.ownerId)
    )
  }

  def findAllByOwnerId(ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[List[Repo]] = {
    withSQL(
      select
        .from[Repo](Repo as r)
        .where.eq(r.ownerId, ownerId)
    ).map(Repo.db(r)).list()
  }

  def find(id: Long, ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Repo] = {
    withSQL(
      select
        .from[Repo](Repo as r)
        .where.eq(r.id, id).and.eq(r.ownerId, ownerId)
    ).map(Repo.db(r)).single().future().flatMap { maybeRepo =>
      maybeRepo.fold(Future.failed[Repo](RepoNotFound()))(Future.successful)
    }
  }

  def create(id: Long, name: String, fullName: String, description: String, htmlUrl: String, ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Long] = {
    withSQL(
      insert.into(Repo).namedValues(
        column.githubId -> id,
        column.name -> name,
        column.fullName -> fullName,
        column.description -> description,
        column.htmlUrl -> htmlUrl,
        column.ownerId -> ownerId
      ).returningId
    ).updateAndReturnGeneratedKey()
  }

}

case class RepoNotFound() extends Exception {
  override def getMessage = "Repo not found"
}