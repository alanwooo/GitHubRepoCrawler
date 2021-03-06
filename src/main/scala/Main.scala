/**
 * Created by lastland on 15/6/16.
 */

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import com.liyaos.metabenchmark.disl.{DiSLSbt, DiSLRun, DiSLMvn, DiSLJava}
import com.typesafe.scalalogging.StrictLogging
import scala.slick.driver.H2Driver.simple._
import Database.dynamicSession
import scala.slick.jdbc.StaticQuery._
import scala.concurrent.ExecutionContext.Implicits.global
import com.liyaos.metabenchmark.database.{GitHubRepoDatabase, Tables}
import com.liyaos.metabenchmark.tools._
import Tables._

object Main extends App with StrictLogging {
  args.toList match {
    case "init" :: Nil =>
      try {
        GitHubRepoDatabase.DB.withDynSession {
          gitHubRepos.ddl.create
        }
      } catch {
        case e => println(e)
      }
      DiSLJava.install()
      DiSLSbt.install()
      DiSLMvn.install()
    case "reset" :: Nil =>
      GitHubRepoDatabase.DB.withDynSession {
        updateNA("DROP ALL OBJECTS DELETE FILES").execute
      }
    case "run" :: Nil =>
      val repos = new GitHubRepos
      val stream = repos.searchLanguage("Java") #::: repos.searchLanguage("Scala")
      for (repo <- stream) {
        GitHubRepoDatabase.DB.withDynSession {
          GitHubRepoDatabase.addRepo(repo)
        }
      }
    case "filter" :: Nil =>
      val dir = "./tmp/"
      val result = new ConcurrentHashMap[GitHubRepo, Unit]()
      new File(dir).mkdir
      val disl = new DiSLRun
      GitHubRepoDatabase.DB.withDynSession {
        for (repo <- gitHubRepos) {
          val r = repo.toGitHubRepo
          val f = GitHubRepoTestRunner.run(disl, r, "java.util.concurrent.ThreadPoolExecutor",
            downloadDir = dir)
          f onFailure {
            case e: NoRecognizableBuildException =>
              logger.debug(s"${r} with no recognizable build.")
            case FilterOutException(phase) =>
              logger.debug(s"${r} filtered out at ${phase}.")
            case DownloadFailedException(f) =>
              logger.debug(s"${r} download failed at ${f}.")
            case e: Throwable => logger.info(
              s"""${r}: ${e}
           | ${e.getStackTrace.mkString("\n")}""".stripMargin)
          }
        }
      }
    case "tests" :: Nil =>
      val dir = "./tmp/"
      val files = new File(dir).list()
      val disl = new DiSLRun
      for (file <- files) {
        val tester = LocalGitHubRepo.getRepo(dir + file).getTester()
        disl.run {
          val exitCode = tester.test()
          logger.info(s"Test results for $file: $exitCode")
        }
      }
  }
}