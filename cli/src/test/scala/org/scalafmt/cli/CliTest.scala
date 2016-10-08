package org.scalafmt.cli

import java.io.File
import java.nio.file.Files

import org.scalafmt.Error.MisformattedFile
import org.scalafmt.config
import org.scalafmt.config.Config
import org.scalafmt.config.ScalafmtConfig
import org.scalafmt.util.DiffAssertions
import org.scalafmt.util.FileOps
import org.scalafmt.util.GitOps
import org.scalafmt.util.logger
import org.scalatest.FunSuite

object FileTestOps {
  def createDir(layout: String): File = {
    val root = File.createTempFile("root", "root")
    root.delete()
    root.mkdir()
    layout.split("(?=\n/)").foreach { row =>
      val path :: contents :: Nil =
        row.stripPrefix("\n").split("\n", 2).toList
      val file = new File(root, path)
      file.getParentFile.mkdirs()
      FileOps.writeFile(file, contents)
    }
    root
  }

  def dir2string(file: File): String = {
    FileOps
      .listFiles(file)
      .map { path =>
        val contents = FileOps.readFile(path)
        s"""|${path.stripPrefix(file.getPath)}
            |$contents""".stripMargin
      }
      .mkString("\n")
  }

}

class CliTest extends FunSuite with DiffAssertions {
  import FileTestOps._
  val unformatted = """
                      |object a    extends   App {
                      |pr(
                      |
                      |"h")
                      |}
                    """.stripMargin
  // Using maxColumn 10 just to see the CLI uses the custom style.
  val expected10 = """|object a
                      |    extends App {
                      |  pr(
                      |    "h")
                      |}""".stripMargin
  val formatted = """|object a extends App {
                     |  pr("h")
                     |}""".stripMargin
  val sbtOriginal =
    """|lazy val x = project
       |   lazy val y    = project
       |   """.stripMargin

  val sbtExpected =
    """|lazy val x = project
       |lazy val y = project""".stripMargin

  def gimmeConfig(string: String): ScalafmtConfig =
    Config.fromHocon(string) match {
      case Right(e) => e
      case Left(e) => throw e
    }

  test("scalafmt -i --file tmpFile") {
    val tmpFile = Files.createTempFile("prefix", ".scala")
    Files.write(tmpFile, unformatted.getBytes)
    val args = Array(
      "--config",
      "\"maxColumn=7\"",
      "--in-place",
      "--files",
      tmpFile.toFile.getPath
    )
    val formatInPlace = Cli.getConfig(args).get
    Cli.run(formatInPlace)
    val obtained = FileOps.readFile(tmpFile.toString)
    assertNoDiff(obtained, expected10)
  }

  test("scalafmt --test --file tmpFile") {
    val tmpFile = Files.createTempFile("prefix", ".scala")
    Files.write(tmpFile, unformatted.getBytes)
    val args = Array(
      "--files",
      tmpFile.toFile.getPath,
      "--test"
    )
    val formatInPlace = Cli.getConfig(args).get
    intercept[MisformattedFile] {
      Cli.run(formatInPlace)
    }
  }

  test("scalafmt -i ignores non-scala files") {
    val tmpFile = Files.createTempFile("prefix", "suffix")
    Files.write(tmpFile, unformatted.getBytes)
    val formatInPlace =
      CliOptions.default
        .copy(inPlace = true)
        .withFiles(Seq(tmpFile.toFile))
    Cli.run(formatInPlace)
    val obtained = FileOps.readFile(tmpFile.toString)
    assertNoDiff(obtained, unformatted)
  }

//  val root = createDir(
//    """/foo/bar
//      |println(1)
//      |yea
//      |/foo/kaz
//      |whoo
//    """.stripMargin
//  )

  test("handles .scala and .sbt files") {
    val input = createDir(
      s"""|/foobar.scala
          |object    A {  }
          |/foo.sbt
          |lazy   val x   = project
          |""".stripMargin
    )
    val expected =
      s"""|/foo.sbt
          |lazy val x = project
          |
          |/foobar.scala
          |object A {}
          |""".stripMargin
    val options = Cli
      .getConfig(
        Array(
          "--files",
          input.getAbsolutePath,
          "-i"
        ))
      .get
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }

  test("excludefilters are respected") {
    val input = createDir(
      s"""|/foo.sbt
          |lazy   val x   = project
          |/target/generated.scala
          |object    AAAAAAIgnoreME   {  }
          |""".stripMargin
    )
    val expected =
      s"""|/foo.sbt
          |lazy val x = project
          |
          |/target/generated.scala
          |object    AAAAAAIgnoreME   {  }
          |""".stripMargin
    val options = Cli
      .getConfig(
        Array(
          "--files",
          input.getAbsolutePath,
          "--exclude",
          "target",
          "-i"
        ))
      .get
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }
}

class GitTest extends FunSuite with DiffAssertions {
  import FileTestOps._

  test("by default format all project") {
    val input =
      createDir(
        """|/foo.scala
           |object    FormatMe {
           |  val x = 1
           |}
           |/.scalafmt.conf
           |maxColumn = 2
           |""".stripMargin
      )
    val expected =
      """|/.scalafmt.conf
         |maxColumn = 2
         |
         |/foo.scala
         |object FormatMe {
         |  val x =
         |    1
         |}
         |
         |""".stripMargin
    val fakeGitOps: GitOps = new FakeGitOps(input)
    val config = CliOptions.default.copy(gitOps = fakeGitOps)
    Cli.run(config)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }
}

class FakeGitOps(root: File) extends GitOps {
  override def lsTree = {
    val res = FileOps.listFiles(root)
    logger.elem(res)
    res
  }

  override def rootDir = Some(root.getAbsolutePath)
}
