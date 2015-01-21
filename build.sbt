name := "metadata-merger"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.5"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  ws,
  filters,

  "org.postgresql"       %  "postgresql"                    % "9.3-1102-jdbc41",
  "com.github.tototoshi" %% "play-flyway"                   % "1.2.0",

  "com.github.mauricio"  %% "postgresql-async"              % "0.2.15",
  "org.scalikejdbc"      %% "scalikejdbc-async"             % "0.5.4",
  "org.scalikejdbc"      %% "scalikejdbc-async-play-plugin" % "0.5.4",

  "org.webjars"          %% "webjars-play"                  % "2.3.0-2",
  "org.webjars"          %  "requirejs"                     % "2.1.15",
  "org.webjars"          %  "require-css"                   % "0.1.8-1",
  "org.webjars"          %  "angularjs"                     % "1.2.27",
  "org.webjars"          %  "angular-ui-bootstrap"          % "0.12.0",
  "org.webjars"          %  "bootstrap"                     % "3.3.2",
  "org.webjars"          %  "angular-highlightjs"           % "0.3.2-1",
  "org.webjars"          %  "diff2html"                     % "0.0.4-3",
  "org.webjars"          %  "diff"                          % "1.2.1",

  "org.scalatestplus"    %% "play"                          % "1.2.0"     % "test"
)

pipelineStages := Seq(digest, gzip)
