ThisBuild / scalaVersion := "2.12.4"
ThisBuild / crossScalaVersions := Seq("2.12.4", "2.11.12")
ThisBuild / scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11")
ThisBuild / libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.0.1" % Test,
  "com.typesafe.play" %% "play-json" % "2.6.2"
  // "edu.berkeley.cs" %% "chisel3" % "3.1.0"
)

lazy val rocketchip = RootProject(file("designs/rocket-chip"))
lazy val testchipip = (project in file("designs/testchipip")).dependsOn(rocketchip)
lazy val sifiveip = (project in file("designs/sifiveip")).dependsOn(rocketchip)
lazy val hwacha = (project in file("designs/hwacha")).dependsOn(rocketchip)
lazy val root = (project in file(".")).dependsOn(testchipip, sifiveip, hwacha)
