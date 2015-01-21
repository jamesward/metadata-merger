package models

import play.api.libs.Crypto
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scalikejdbc.WrappedResultSet
import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._
import utils.AuthInfo

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class Org(id: Long, sfId: String, name: String, ownerName: String, edition: String, accessToken: String, refreshToken: String, instanceUrl: String, ownerId: String) {

  private val o = Org.column

  def updateAccessToken(accessToken: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Org] = {
    withSQL(
      update(Org)
        .set(o.accessToken -> Crypto.encryptAES(accessToken))
        .where.eq(o.id, id)
    ).update().future().map(_ => copy(accessToken = accessToken))
  }

}

object Org extends SQLSyntaxSupport[Org] {

  implicit val jsonWrites: Writes[Org] = (
    (JsPath \ "id").write[Long] ~
    (JsPath \ "name").write[String] ~
    (JsPath \ "owner_name").write[String] ~
    (JsPath \ "edition").write[String] ~
    (JsPath \ "login_url").write[String]
  )(org => (org.id, org.name, org.ownerName, org.edition, org.instanceUrl + "/secur/frontdoor.jsp?sid=" + org.accessToken))

  override val columnNames = Seq("id", "sf_id", "name", "owner_name", "edition", "access_token", "refresh_token", "instance_url", "owner_id")

  lazy val o = Org.syntax

  def db(o: SyntaxProvider[Org])(rs: WrappedResultSet): Org = db(o.resultName)(rs)

  def db(o: ResultName[Org])(rs: WrappedResultSet): Org = {
    Org(
      rs.long(o.id),
      rs.string(o.sfId),
      rs.string(o.name),
      rs.string(o.ownerName),
      rs.string(o.edition),
      Crypto.decryptAES(rs.string(o.accessToken)),
      Crypto.decryptAES(rs.string(o.refreshToken)),
      rs.string(o.instanceUrl),
      rs.string(o.ownerId)
    )
  }

  def find(id: Long, ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Org] = {
    withSQL(
      select
        .from[Org](Org as o)
        .where.eq(o.id, id).and.eq(o.ownerId, ownerId)
    ).map(Org.db(o)).single().future().flatMap { maybeOrg =>
      maybeOrg.fold(Future.failed[Org](OrgNotFound()))(Future.successful)
    }
  }

  def findAllByOwnerId(ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[List[Org]] = {
    withSQL(
      select
        .from[Org](Org as o)
        .where.eq(o.ownerId, ownerId)
    ).map(Org.db(o)).list()
  }

  def create(sfId: String, name: String, ownerName: String, edition: String, accessToken: String, refreshToken: String, instanceUrl: String, ownerId: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Long] = {
    withSQL(
      insert.into(Org).namedValues(
        column.sfId -> sfId,
        column.name -> name,
        column.ownerName -> ownerName,
        column.edition -> edition,
        column.accessToken -> Crypto.encryptAES(accessToken),
        column.refreshToken -> Crypto.encryptAES(refreshToken),
        column.instanceUrl -> instanceUrl,
        column.ownerId -> ownerId
      ).returningId
    ).updateAndReturnGeneratedKey()
  }

  def updateTokens(ownerId: String, orgId: String, authInfo: AuthInfo)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Int] = {
    withSQL(
      update(Org).set(
        column.accessToken -> Crypto.encryptAES(authInfo.accessToken),
        column.refreshToken -> Crypto.encryptAES(authInfo.refreshToken)
      ).where.eq(o.ownerId, ownerId).and.eq(o.sfId, orgId)
    ).update()
  }

}

case class OrgNotFound() extends Exception {
  override def getMessage = "Org not found"
}