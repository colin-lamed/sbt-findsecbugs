package nl.codestar.sbtfindsecbugs

import java.io.File

import sbt._
import Keys._

import Priority._

object FindSecBugs extends AutoPlugin {
  private val exitCodeOk: Int = 0
  private val exitCodeClassesMissing: Int = 2

  // Later versions introduce a failure: "java.lang.IllegalArgumentException: 1 is not a value stack offset"
  // This will be fixed in 4.9.4 (not released yet): https://github.com/spotbugs/spotbugs/issues/3320
  private val spotbugsVersion = "4.9.1"
  private val findsecbugsPluginVersion = "1.14.0"
  private val asmVersion = "9.5"
  private val pluginId = "com.h3xstream.findsecbugs" % "findsecbugs-plugin" % findsecbugsPluginVersion

  private val FindsecbugsConfig = sbt.config("findsecbugs")
    .describedAs("Classpath configuration for SpotBugs")
  private val FindSecBugsTag = Tags.Tag("findSecBugs")

  override def trigger = AllRequirements

  object autoImport {
    lazy val findSecBugsExcludeFile = settingKey[Option[File]]("The FindBugs exclude file for findsecbugs")
    lazy val findSecBugsFailOnMissingClass = settingKey[Boolean]("Consider 'missing class' flag as error")
    lazy val findSecBugsParallel = settingKey[Boolean]("Perform FindSecurityBugs check in parallel (or not)")
    lazy val findSecBugsPriorityThreshold = settingKey[Priority]("Set the priority threshold. Bug instances must be at least as important as this priority to be reported")
    lazy val findSecBugs = taskKey[Unit]("Perform FindSecurityBugs check")
  }

  import autoImport._

  override lazy val projectSettings =
    inConfig(FindsecbugsConfig)(Defaults.configSettings) ++
      inTask(findSecBugs)(Seq(
        forkOptions := Defaults.forkOptionsTask.value,
        connectInput := true,
        javaOptions += "-Xmx1024m"
      )) ++ Seq(
        findSecBugsExcludeFile := None,
        findSecBugsFailOnMissingClass := true,
        findSecBugsParallel := true,
        findSecBugsPriorityThreshold := Low,
        concurrentRestrictions in Global ++= (if (findSecBugsParallel.value) Nil else Seq(Tags.exclusive(FindSecBugsTag))),
        ivyConfigurations += FindsecbugsConfig,
        libraryDependencies ++= Seq(
          "com.github.spotbugs" % "spotbugs" % spotbugsVersion % FindsecbugsConfig,
          pluginId % FindsecbugsConfig,
          "org.slf4j" % "slf4j-simple" % "2.0.17" % FindsecbugsConfig,
          // Override transitive asm version for Java 21 compatibility
          "org.ow2.asm" % "asm"          % asmVersion,
          "org.ow2.asm" % "asm-analysis" % asmVersion,
          "org.ow2.asm" % "asm-commons"  % asmVersion,
          "org.ow2.asm" % "asm-tree"     % asmVersion,
          "org.ow2.asm" % "asm-util"     % asmVersion
        ),
        findSecBugs := (findSecBugsTask tag FindSecBugsTag).value,
        findSecBugs / artifactPath := crossTarget.value / "findsecbugs" / "report.html"
      )

  private def findSecBugsTask() = Def.task {
    def commandLineClasspath(classpathFiles: Seq[File]): String = PathFinder(classpathFiles.filter(_.exists)).absString
    lazy val log = Keys.streams.value.log
    lazy val output = (findSecBugs / artifactPath).value
    lazy val classpath = commandLineClasspath((FindsecbugsConfig / dependencyClasspath).value.files)
    lazy val auxClasspath = commandLineClasspath((Compile / dependencyClasspath).value.files)
    lazy val classDirs = (Compile / products).value
    lazy val excludeFile = findSecBugsExcludeFile.value

    lazy val updateReport = update.value
    lazy val pluginList: String = findPluginJar(updateReport).getOrElse(
      sys.error(s"Failed to find resolved JAR for $pluginId")
    ).getAbsolutePath
    lazy val forkOptions0 = (findSecBugs / forkOptions).value
      // can't do this through settings - `streams` is a task.
      .withOutputStrategy(LoggedOutput(new FindBugsLogger(log)))

    IO.createDirectory(output.getParentFile)
    IO.withTemporaryDirectory { tempdir =>
      val includeFile = createIncludesFile(tempdir)
      val filteredClassDirs = classDirs.filter(_.exists)
      if (filteredClassDirs.nonEmpty) {
        val filteredClassDirsStr = filteredClassDirs.map(cd => s"'$cd'").mkString(", ")
        log.info(s"Performing FindSecurityBugs check of $filteredClassDirsStr...")
        val opts = List(
          "-cp", classpath,
          "edu.umd.cs.findbugs.LaunchAppropriateUI",
          "-textui",
          "-exitcode",
          s"-html:plain.xsl=${output.getAbsolutePath}",
          "-nested:true",
          "-auxclasspath", auxClasspath,
          s"-${findSecBugsPriorityThreshold.value.name}",
          "-effort:max",
          "-pluginList", pluginList,
          "-noClassOk"
        ) ++
          List("-include", includeFile.getAbsolutePath) ++
          excludeFile.toList.flatMap(f => List("-exclude", f.getAbsolutePath)) ++
          filteredClassDirs.map(_.getAbsolutePath)
        val result = Fork.java(forkOptions0, opts)
        result match {
          case `exitCodeOk` =>
            //noop
          case `exitCodeClassesMissing` if !findSecBugsFailOnMissingClass.value =>
            //noop
          case _ =>
            sys.error(s"Security issues found. Please review them in $output")
        }
      }
      else {
        val classDirsStr = classDirs.map(cd => s"'$cd'").mkString(", ")
        log.warn(s"Class directory list ($classDirsStr) contains no existing directories, not running scan")
      }
    }
  }

  private def createIncludesFile(tempdir: sbt.File): sbt.File = {
    val includeFile = tempdir / "include.xml"
    val includeXml =
      """
        |<FindBugsFilter>
        |    <Match>
        |        <Bug category="SECURITY"/>
        |    </Match>
        |</FindBugsFilter>
      """.stripMargin
    IO.write(includeFile, includeXml)
    includeFile
  }

  private def findPluginJar(updateReport: UpdateReport): Option[File] =
    updateReport.configuration(FindsecbugsConfig)
      .flatMap(_.modules.find { resolvedModule =>
        // We don't compare the revisions, etc. - resolution can change those.
        resolvedModule.module.organization == pluginId.organization &&
        resolvedModule.module.name == pluginId.name
      })
      .flatMap(_.artifacts.collectFirst {
        case (artifact, file) if artifact.`type` == Artifact.DefaultType => file
      })

  /**
    * FindBugs logs everyting to stderr, even when everything was succesful.
    * This logger makes that logging a little bit smarter.
    */
  class FindBugsLogger(underlying: Logger) extends Logger {
    override def log(level: Level.Value, message: => String): Unit = (level, message.toLowerCase) match {
      case (Level.Debug, _) =>
        underlying.log(Level.Debug, message)
      case (_, s) if s.contains("error") =>
        underlying.log(Level.Error, message)
      case (_, s) if s.contains("warning") =>
        underlying.log(Level.Warn, message)
      case _ =>
        underlying.log(Level.Info, message)
    }
    override def trace(t: => Throwable): Unit = underlying.trace(t)
    override def success(message: => String): Unit = underlying.success(message)
  }

}
