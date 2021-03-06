import Dependencies._
import Shared._
import sbtbuildinfo.Plugin._

organization := "noootsab"

name := "spark-notebook"

scalaVersion := defaultScalaVersion

val SparkNotebookSimpleVersion = "0.6.2-SNAPSHOT"

version in ThisBuild <<= (scalaVersion, sparkVersion, hadoopVersion, withHive, withParquet) { (sc, sv, hv, h, p) =>
  s"$SparkNotebookSimpleVersion-scala-$sc-spark-$sv-hadoop-$hv" + (if (h) "-with-hive" else "") + (if (p) "-with-parquet" else "")
}

maintainer := "Andy Petrella" //Docker

enablePlugins(UniversalPlugin)

enablePlugins(DockerPlugin)

enablePlugins(GitVersioning)

enablePlugins(GitBranchPrompt)

import uk.gov.hmrc.gitstamp.GitStampPlugin._

net.virtualvoid.sbt.graph.Plugin.graphSettings

import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._

import com.typesafe.sbt.packager.docker._

// java image based on ubuntu trusty rather than debian jessie (to use mesosphere distros)
// build it like this:
// ```
// docker build -t="dockerfile/ubuntu" github.com/dockerfile/ubuntu
// git clone https://github.com/dockerfile/java.git
// cd java
// cd openjdk-7-jdk
// docker build -t="dockerfile/java:openjdk-7-jdk" .
// ```
dockerBaseImage := "dockerfile/java:openjdk-7-jdk"

dockerCommands ++= Seq(
  Cmd("USER", "root"),
  Cmd("RUN", "apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF"),
  Cmd("RUN", "echo \"deb http://repos.mesosphere.io/ubuntu trusty main\" | tee /etc/apt/sources.list.d/mesosphere.list"),
  Cmd("RUN", "/usr/bin/apt-get -y update --fix-missing"),
  Cmd("RUN", s"/usr/bin/apt-get -y install mesos=$mesosVersion-1.0.ubuntu1404"), //ubuntu 14.04 is base for java:latest → https://github.com/dockerfile/ubuntu/blob/master/Dockerfile
  Cmd("ENV", s"MESOS_JAVA_NATIVE_LIBRARY /usr/local/lib/libmesos-$mesosVersion.so"),
  Cmd("ENV", s"MESOS_LOG_DIR /var/log/mesos")
)

dockerExposedVolumes ++= Seq("/opt/docker", "/opt/docker/notebooks", "/opt/docker/logs")

dockerExposedPorts ++= Seq(9000, 9443) //Docker

dockerRepository := Some("andypetrella") //Docker

packageName in Docker := "spark-notebook"

ivyScala := ivyScala.value map {
  _.copy(overrideScalaVersion = true)
}

parallelExecution in Test in ThisBuild := false

// these java options are for the forked test JVMs
javaOptions in ThisBuild ++= Seq("-Xmx512M", "-XX:MaxPermSize=128M")

resolvers in ThisBuild ++= Seq(
  Resolver.mavenLocal,
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  Resolver.typesafeIvyRepo("releases"),
  Resolver.typesafeIvyRepo("snapshots"),
  "cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos",
  // docker
  "softprops-maven" at "http://dl.bintray.com/content/softprops/maven",
  //spark cutting edge
  "spark 1.5.0-rc2" at "https://repository.apache.org/content/repositories/orgapachespark-1141"
)

EclipseKeys.skipParents in ThisBuild := false

compileOrder := CompileOrder.Mixed

publishMavenStyle := false

javacOptions ++= Seq("-Xlint:deprecation", "-g")

scalacOptions += "-deprecation"

scalacOptions ++= Seq("-Xmax-classfile-name", "100")

//scriptClasspath := Seq("*")

scriptClasspath in batScriptReplacements := Seq("*")

batScriptExtraDefines += {
  "set \"APP_CLASSPATH=%YARN_CONF_DIR%;%HADOOP_CONF_DIR%;%EXTRA_CLASSPATH%;%APP_CLASSPATH%\""
}

val ClasspathPattern = "declare -r app_classpath=\"(.*)\"\n".r

bashScriptDefines := bashScriptDefines.value.map {
  case ClasspathPattern(classpath) =>
    s"""declare -r app_classpath="$${YARN_CONF_DIR}:$${HADOOP_CONF_DIR}:$${EXTRA_CLASSPATH}:${classpath}"\n"""
  case entry => entry
}

dependencyOverrides += "log4j" % "log4j" % "1.2.16"

dependencyOverrides += guava

enablePlugins(DebianPlugin)

sharedSettings

libraryDependencies ++= playDeps

libraryDependencies ++= List(
  akka,
  akkaRemote,
  akkaSlf4j,
  cache,
  commonsIO,
  // ↓ to fix java.lang.IllegalStateException: impossible to get artifacts when data has
  //   not been loaded. IvyNode = org.apache.commons#commons-exec;1.1
  //   encountered when using hadoop "2.0.0-cdh4.2.0"
  commonsExec,
  commonsCodec,
  //scala stuffs
  "org.scala-lang" % "scala-library" % defaultScalaVersion,
  "org.scala-lang" % "scala-reflect" % defaultScalaVersion,
  "org.scala-lang" % "scala-compiler" % defaultScalaVersion
)

//for aether
libraryDependencies <++= scalaBinaryVersion {
  case "2.10" => Nil
  case "2.11" => List(ningAsyncHttpClient)
}

lazy val sparkNotebook = project.in(file(".")).enablePlugins(play.PlayScala).enablePlugins(SbtWeb)
  .aggregate(tachyon, subprocess, observable, common, spark, kernel)
  .dependsOn(tachyon, subprocess, observable, common, spark, kernel)
  .settings(sharedSettings: _*)
  .settings(
    bashScriptExtraDefines <+= (version, scalaBinaryVersion, scalaVersion, sparkVersion, hadoopVersion, withHive, withParquet) map { (v, sbv, sv, pv, hv, wh, wp) =>
      """export ADD_JARS="${ADD_JARS},${lib_dir}/$(ls ${lib_dir} | grep common.common | head)""""
    },
    mappings in Universal ++= directory("notebooks"),
    mappings in Docker ++= directory("notebooks")
  )
  .settings(includeFilter in(Assets, LessKeys.less) := "*.less")
  .settings(unmanagedSourceDirectories in Compile <<= (scalaSource in Compile)(Seq(_))) //avoid app-2.10 and co to be created
  .settings(initialCommands += ConsoleHelpers.cleanAllOutputs)
  .settings(
    git.useGitDescribe := true,
    git.baseVersion := SparkNotebookSimpleVersion
  )
  .settings(
    gitStampSettings: _*
  )

lazy val subprocess = project.in(file("modules/subprocess"))
  .settings(libraryDependencies ++= playDeps)
  .settings(
    libraryDependencies ++= {
      Seq(
        akka,
        akkaRemote,
        akkaSlf4j,
        commonsIO,
        commonsExec,
        log4j
      )
    }
  )
  .settings(sharedSettings: _*)
  .settings(sparkSettings: _*)


lazy val observable = Project(id = "observable", base = file("modules/observable"))
  .dependsOn(subprocess)
  .settings(
    libraryDependencies ++= Seq(
      akkaRemote,
      akkaSlf4j,
      slf4jLog4j,
      rxScala
    )
  )
  .settings(sharedSettings: _*)

lazy val common = Project(id = "common", base = file("modules/common"))
  .dependsOn(observable)
  .settings(
    libraryDependencies ++= Seq(
      akka,
      log4j,
      scalaZ
    ),
    libraryDependencies ++= depsToDownloadDeps(scalaBinaryVersion.value, sbtVersion.value),
    // plotting functionality
    libraryDependencies ++= Seq(
      bokeh
    ), // ++ customJacksonScala
    unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / ("scala-" + scalaBinaryVersion.value),
    unmanagedSourceDirectories in Compile +=
      (sourceDirectory in Compile).value / ((sparkVersion.value.takeWhile(_ != '-').split("\\.").toList match {
        case "1"::x::_ if x.toInt < 3 => "pre-df"
        case x                        => "post-df"
      }))
  )
  .settings(
    wispSettings
  )
  .settings(sharedSettings: _*)
  .settings(sparkSettings: _*)
  .settings(buildInfoSettings: _*)
  .settings(
    sourceGenerators in Compile <+= buildInfo,
    buildInfoKeys :=  Seq[BuildInfoKey](
                        "sparkNotebookVersion" → SparkNotebookSimpleVersion,
                        scalaVersion,
                        sparkVersion,
                        hadoopVersion,
                        withHive,
                        withParquet,
                        jets3tVersion,
                        jlineDef,
                        sbtVersion,
                        git.formattedShaVersion,
                        BuildInfoKey.action("buildTime") {
                          val formatter = new java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy")
                          formatter.format(new java.util.Date(System.currentTimeMillis))
                        }
                      ),
    buildInfoPackage := "notebook"
  )


lazy val spark = Project(id = "spark", base = file("modules/spark"))
  .dependsOn(common, subprocess, observable)
  .settings(
    libraryDependencies ++= Seq(
      akkaRemote,
      akkaSlf4j,
      slf4jLog4j,
      commonsIO
    ),
    libraryDependencies ++= Seq(
      jlineDef.value._1 % "jline" % jlineDef.value._2,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value
    ),
    unmanagedSourceDirectories in Compile +=
      (sourceDirectory in Compile).value / ("scala_" + ((scalaBinaryVersion.value, sparkVersion.value.takeWhile(_ != '-')) match {
        case (v, sv) if v startsWith "2.10" => "2.10" + "/spark-" + sv
        case (v, sv) if v startsWith "2.11" => "2.11" + "/spark-" + sv
        case (v, sv) => throw new IllegalArgumentException("Bad scala version: " + v)
      }))
  )
  .settings(sharedSettings: _*)
  .settings(sparkSettings: _*)

lazy val tachyon = Project(id = "tachyon", base = file("modules/tachyon"))
  .settings(sharedSettings: _*)
  .settings(tachyonSettings: _*)

lazy val kernel = Project(id = "kernel", base = file("modules/kernel"))
  .dependsOn(common, subprocess, observable, spark)
  .settings(
    libraryDependencies ++= Seq(
      akkaRemote,
      akkaSlf4j,
      slf4jLog4j,
      commonsIO
    ),
    unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / ("scala-" + scalaBinaryVersion.value)
  )
  .settings(sharedSettings: _*)
