package models

import play.api.libs.Crypto
import scalikejdbc._
import scalikejdbc.async._
import scalikejdbc.async.FutureImplicits._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/*
case class Team(id: Long, name: String, orgs: Seq[Org], teamMembers: Seq[TeamMember]) {

  private val tm = TeamMember.column
  private val o = Org.column

  def addTeamMember(name: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Long] = {
    withSQL(
      insert.into(TeamMember).namedValues(
        tm.name -> name,
        tm.teamId -> id
      ).returningId
    ).updateAndReturnGeneratedKey()
  }

  def addOrg(name: String, accessToken: String, refreshToken: String, instanceUrl: String, ownerId: Long)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Long] = {
    withSQL(
      insert.into(Org).namedValues(
        o.name -> name,
        o.accessToken -> Crypto.encryptAES(accessToken),
        o.refreshToken -> Crypto.encryptAES(refreshToken),
        o.instanceUrl -> instanceUrl,
        o.teamId -> id,
        o.ownerId -> ownerId
      ).returningId
    ).updateAndReturnGeneratedKey()
  }

}

object Team extends SQLSyntaxSupport[Team] {

  override val columnNames = Seq("id", "name")

  lazy val t = Team.syntax
  lazy val otm = TeamMember.syntax("otm")

  def apply(g: SyntaxProvider[Team])(rs: WrappedResultSet): Team = apply(g.resultName)(rs)
  def apply(g: ResultName[Team])(rs: WrappedResultSet): Team = {
    Team(rs.long(g.id), rs.string(g.name), Seq.empty[Org], Seq.empty[TeamMember])
  }

  def find(id: Long)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Team] = {
    val sql = withSQL(
      select
        .from[Team](Team as t)
        .leftJoin(Org as Org.o).on(t.id, Org.o.teamId)
        .leftJoin(TeamMember as otm).on(Org.o.ownerId, otm.id)
        .leftJoin(TeamMember as TeamMember.tm).on(t.id, TeamMember.tm.teamId)
        .where.eq(t.id, id)
    )

    sql
      .one(Team(t))
      .toManies(Org.opt(Org.o, otm), TeamMember.opt(TeamMember.tm))
      .map((team, orgs, teamMembers) => team.copy(orgs = orgs, teamMembers = teamMembers))
      .single()
      .future
      .flatMap(_.fold(Future.failed[Team](new Error(s"Team $id could not be retrieved")))(Future.successful))
  }

  def create(name: String)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Team] = {
    for {
      id <- withSQL(insert.into(Team).namedValues(column.name -> name).returningId).updateAndReturnGeneratedKey()
    } yield Team(id, name, Seq.empty[Org], Seq.empty[TeamMember])
  }

  def destroy(team: Team)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[Int] = {
    delete.from(Team).where.eq(column.id, team.id)
  }

  def destroyCascade(team: Team)(implicit session: AsyncDBSession = AsyncDB.sharedSession): Future[(Int, Int)] = {
    AsyncDB.localTx { implicit tx =>
      for {
        orgDeletes <- Org.destroyTeam(team)
        teamMemberDeletes <- TeamMember.destroyTeam(team)
        teamDeletes <- destroy(team)
      } yield (teamMemberDeletes, teamDeletes)
    }
  }

}
*/