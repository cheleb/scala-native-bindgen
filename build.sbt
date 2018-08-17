import scala.sys.process._
import scala.util.Try
import org.scalanative.bindgen.sbt.ScalaNativeBindgenPlugin
import BindingHelpers._

addCommandAlias("verify", "; test ; ^scripted ; docs/makeSite")

val Versions = new {
  val scala210 = "2.10.6"
  val scala211 = "2.11.12"
  val scala212 = "2.12.6"
  val sbt013   = "0.13.17"
  val sbt1     = "1.1.6"
}

inThisBuild(
  Def.settings(
    organization := "org.scala-native.bindgen",
    licenses := Seq(
      "BSD 3-Clause" -> url("https://www.scala-lang.org/license/")),
    homepage := Some(url("https://kornilova-l.github.io/scala-native-bindgen")),
    scalacOptions ++= Seq(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-encoding",
      "utf8"
    ),
    parallelExecution in Global := false,
    scmInfo := Some(
      ScmInfo(url("https://github.com/kornilova-l/scala-native-bindgen"),
              "scm:git:git@github.com:kornilova-l/scala-native-bindgen.git")),
    developers := List(
      Developer(
        id = "kornilova-l",
        name = "Liudmila Kornilova",
        email = "kornilova-l@users.noreply.github.com",
        url = url("https://github.com/kornilova-l")
      ),
      Developer(
        id = "jonas",
        name = "Jonas Fonseca",
        email = "jonas@users.noreply.github.com",
        url = url("https://github.com/jonas")
      )
    )
  ))

val root = project("scala-native-bindgen")
  .in(file("."))
  .aggregate(
    tests,
    samples,
    tools,
    sbtPlugin,
    bindings,
    docs
  )
  .enablePlugins(ReleasePlugin)
  .settings(
    publish / skip := true,
    releaseCrossBuild := false,
    releaseVersionFile := target.value / "unused-version.sbt",
    releaseProcess := {
      import ReleaseTransformations._

      val setReleaseVersions: String => State => State =
        v => _.put(ReleaseKeys.versions, (v, v))

      Seq[ReleaseStep](
        checkSnapshotDependencies,
        setReleaseVersions(version.value),
        runClean,
        releaseStepCommandAndRemaining("verify"),
        setReleaseVersion,
        tagRelease,
        releaseStepCommandAndRemaining("+bindings/publish"),
        releaseStepCommandAndRemaining("+tools/publish"),
        releaseStepCommandAndRemaining("^sbt-scala-native-bindgen/publish"),
        pushChanges,
        releaseStepTask(docs / ghpagesPushSite)
      )
    }
  )

lazy val tests = project("tests")
  .dependsOn(tools)
  .settings(
    publish / skip := true,
    Test / fork := true,
    Test / javaOptions += {
      val rootDir = (ThisBuild / baseDirectory).value
      s"-Dbindgen.path=$rootDir/bindgen/target/scala-native-bindgen"
    },
    watchSources += WatchSource(
      baseDirectory.value / "tests" / "samples",
      "*.h" || "*.scala",
      NothingFilter
    ),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % Test
  )
  .aggregate(samples)

lazy val samples = project("samples")
  .in(file("tests/samples"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    publish / skip := true,
    scalaVersion := Versions.scala211,
    libraryDependencies += "com.lihaoyi" %%% "utest" % "0.6.3" % Test,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    nativeLinkStubs := true,
    compileTask("bindgentests", baseDirectory)
  )

lazy val tools = project("tools")
  .settings(
    crossScalaVersions := List(Versions.scala210,
                               Versions.scala211,
                               Versions.scala212)
  )

lazy val sbtPlugin = project("sbt-scala-native-bindgen", ScriptedPlugin)
  .dependsOn(tools)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    Keys.sbtPlugin := true,
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    scriptedLaunchOpts += {
      val rootDir = (ThisBuild / baseDirectory).value
      s"-Dbindgen.path=$rootDir/bindgen/target/scala-native-bindgen"
    },
    buildInfoPackage := "org.scalanative.bindgen.sbt",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      organization,
      BuildInfoKey.map(scmInfo) {
        case (k, v) => "projectUrl" -> v.get.browseUrl
      }
    ),
    publishLocal := publishLocal.dependsOn(tools / publishLocal).value
  )

lazy val docs = project("docs")
  .enablePlugins(GhpagesPlugin, ParadoxSitePlugin, ParadoxMaterialThemePlugin)
  .enablePlugins(ScalaNativePlugin, ScalaNativeBindgenPlugin)
  .settings(
    publish / skip := true,
    scalaVersion := Versions.scala211,
    Test / nativeBindings += {
      NativeBinding((Test / resourceDirectory).value / "vector.h")
        .name("vector")
        .link("vector")
        .packageName("org.example")
    },
    nativeBindgenPath := {
      Some(
        (ThisBuild / baseDirectory).value / "bindgen/target/scala-native-bindgen")
    },
    Test / nativeBindgen / target := (Test / scalaSource).value / "org/example",
    nativeLinkStubs := true,
    compileTask("vector", Test / resourceDirectory),
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.0-SNAP10" % Test,
    Paradox / paradoxProperties ++= Map(
      "github.base_url" -> scmInfo.value.get.browseUrl.toString
    ),
    ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox),
    Paradox / paradoxMaterialTheme := {
      val licenseUrl = scmInfo.value.get.browseUrl.toString + "blob/master/LICENSE.txt"
      (Paradox / paradoxMaterialTheme).value
        .withRepository(scmInfo.value.get.browseUrl.toURI)
        .withColor("indigo", "indigo")
        .withCopyright(
          s"Copyright © Liudmila Kornilova, distributed under the <a href='$licenseUrl'>Scala license</a>.")
    }
  )

lazy val bindings = project("bindings")
  .settings(
    publish / skip := true,
    scalaVersion := Versions.scala211
  )
  .aggregate(
    libiconv,
    libposix,
    libutf8proc
  )

lazy val libiconv = bindingProject("iconv")
  .configure(binding("iconv.h"))
  .settings(
    Test / nativeLinkingOptions ++= {
      // Link with libiconv on macOS.
      Option(System.getProperty("os.name")) match {
        case Some("Mac OS X") => Seq("-liconv")
        case _                => Seq.empty
      }
    }
  )

//#sbt-binding-project
lazy val libposix = bindingProject("posix")
  .configure(binding("fnmatch.h"))
  .configure(binding("regex.h"))
//#sbt-binding-project

lazy val libutf8proc = bindingProject("utf8proc")
  .configure(binding("utf8proc.h", Some("utf8proc")))

def project(name: String, plugged: AutoPlugin*) = {
  val unplugged = Seq(ScriptedPlugin).filterNot(plugged.toSet)

  Project(id = name, base = file(name))
    .disablePlugins(unplugged: _*)
    .enablePlugins(GitPlugin, GitVersioning)
    .settings(
      versionWithGit,
      git.useGitDescribe := true,
      git.remoteRepo := scmInfo.value.get.connection.replace("scm:git:", ""),
      crossSbtVersions := List(Versions.sbt013, Versions.sbt1),
      scalaVersion := {
        (pluginCrossBuild / sbtBinaryVersion).value match {
          case "0.13" => Versions.scala210
          case _      => Versions.scala212
        }
      },
      bintrayOrganization := Some("scala-native-bindgen"),
      bintrayRepository := {
        if (Keys.sbtPlugin.value) "sbt-plugins"
        else "maven"
      },
      publishMavenStyle := Keys.sbtPlugin.value == false,
      Test / publishArtifact := false
    )
}

def compileTask(libname: String, srcDirTask: SettingKey[File]) = Def.settings(
  Test / nativeLinkingOptions += {
    Seq("-L", (Test / target).value.getAbsoluteFile / "bindgen").mkString
  },
  Test / compile := {
    val log            = streams.value.log
    val cwd            = (Test / target).value / "bindgen"
    val compileOptions = (Test / nativeCompileOptions).value
    val cpaths         = (srcDirTask.value.getAbsoluteFile * "*.c").get
    val clangPath      = nativeClang.value.toPath.toAbsolutePath.toString

    cwd.mkdirs()

    def abs(path: File): String =
      path.getAbsolutePath

    def run(command: Seq[String]): Int = {
      log.info("Running " + command.mkString(" "))
      Process(command, cwd) ! log
    }

    val opaths = cpaths.map {
      cpath =>
        val opath = cwd / s"${cpath.getName}.o"
        val command = Seq(clangPath) ++ compileOptions ++ Seq("-c",
                                                              abs(cpath),
                                                              "-o",
                                                              abs(opath))
        val doCompile =
          !opath.exists() || cpath.lastModified() >= opath.lastModified()

        if (doCompile && run(command) != 0) {
          sys.error(s"Failed to compile $cpath")
        }
        opath
    }

    val archivePath = cwd / s"lib$libname.a"
    val archive     = Seq("ar", "cr", abs(archivePath)) ++ opaths.map(abs)
    val doArchive =
      opaths.map(_.lastModified).max >= archivePath.lastModified()

    if (doArchive && run(archive) != 0) {
      sys.error(s"Failed to create archive $archivePath")
    }

    (Test / compile).value
  }
)

// Add Clang's directory include dir with platform specific headers, like stddef.h.
lazy val bindingsExtraArgs = Try {
  val version = "llvm-config --version".!!.trim
  val libDir  = "llvm-config --libdir".!!.trim
  s"-I$libDir/clang/$version/include"
}.toOption

def bindingProject(name: String) = {
  project(s"lib$name")
    .enablePlugins(ScalaNativePlugin, ScalaNativeBindgenPlugin)
    .in(file(s"bindings/$name"))
    .settings(
      scalaVersion := Versions.scala211,
      nativeLinkStubs := true,
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.0-SNAP10" % Test,
      Compile / nativeBindgen / target :=
        (Compile / scalaSource).value / "org/scalanative/bindgen/bindings" / name
    )
}

def binding(header: String, link: Option[String] = None)(
    project: Project): Project = {
  val headerFile = file("/usr/include") / header
  val libname    = project.base.getName
  project.settings(
    inConfig(Compile)(
      Def.settings(
        nativeBindings ++= {
          if (headerFile.exists) Seq {
            NativeBinding(headerFile)
              .name(header.replace(".h", ""))
              .packageName(s"org.scalanative.bindgen.bindings.$libname")
              .maybe(link, _.link)
              .excludePrefix("__")
              .extraArgs(bindingsExtraArgs.toSeq: _*)
              .extraArgs("-D_POSIX_C_SOURCE")
          } else {
            Seq.empty
          }
        }
      )),
    test := (Def.taskDyn {
      if (headerFile.exists)
        Def.task { (Test / test).value } else
        Def.task {
          streams.value.log.warn(
            s"Skipping $libname tests due to missing header file $headerFile")
        }
    }).value
  )
}
