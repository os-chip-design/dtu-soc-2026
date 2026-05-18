scalaVersion := "2.13.14"

scalacOptions ++= Seq(
  "-feature",
  "-language:reflectiveCalls",
)
fork := true

Compile / unmanagedSourceDirectories += baseDirectory.value / "wildcat/src"
Compile / unmanagedSourceDirectories += baseDirectory.value / "wildcat/soc-comm/src"
Compile / unmanagedSourceDirectories += baseDirectory.value / "wildcat/ip-contributions/src"
Compile / unmanagedSourceDirectories += baseDirectory.value / "FloatingPointUnit/src"
Compile / unmanagedSourceDirectories += baseDirectory.value / "picomesh/src"

scalaVersion := "2.13.14"
val chiselVersion = "3.6.1"
addCompilerPlugin("edu.berkeley.cs" %% "chisel3-plugin" % chiselVersion cross CrossVersion.full)
libraryDependencies += "edu.berkeley.cs" %% "chisel3" % chiselVersion
libraryDependencies += "edu.berkeley.cs" %% "chiseltest" % "0.6.2"


libraryDependencies += "com.fazecast" % "jSerialComm" % "[2.0.0,3.0.0)"
libraryDependencies += "net.fornwall" % "jelf" % "0.9.0"