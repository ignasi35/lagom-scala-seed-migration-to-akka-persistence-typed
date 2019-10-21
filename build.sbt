organization in ThisBuild := "org.eggsample"
version in ThisBuild := "1.0-SNAPSHOT"

// the Scala version that will be used for cross-compiled libraries
scalaVersion in ThisBuild := "2.13.0"

val macwire = "com.softwaremill.macwire" %% "macros" % "2.3.3" % "provided"
val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8" % Test

lazy val `smello` = (project in file("."))
  .aggregate(`smello-api`, `smello-impl`, `smello-stream-api`, `smello-stream-impl`)

lazy val `smello-api` = (project in file("smello-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `smello-impl` = (project in file("smello-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceCassandra,
      lagomScaladslKafkaBroker,
      lagomScaladslTestKit,
      macwire,
      scalaTest,
    )
  )
  .settings(lagomForkedTestSettings)
  .dependsOn(`smello-api`)

lazy val `smello-stream-api` = (project in file("smello-stream-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `smello-stream-impl` = (project in file("smello-stream-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslTestKit,
      macwire,
      scalaTest
    )
  )
  .dependsOn(`smello-stream-api`, `smello-api`)
