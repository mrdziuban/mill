package mill.scalalib.publish

import java.math.BigInteger
import java.security.MessageDigest

import ammonite.ops._
import mill.util.Logger

import scalaj.http.HttpResponse

class SonatypePublisher(uri: String,
                        snapshotUri: String,
                        credentials: String,
                        gpgPassphrase: String,
                        log: Logger) {

  private val api = new SonatypeHttpApi(uri, credentials)

  def publish(fileMapping: Seq[(Path, String)], artifact: Artifact): Unit = {
    publishAll(fileMapping -> artifact)
  }
  def publishAll(artifacts: (Seq[(Path, String)], Artifact)*): Unit = {

    val mappings = for ((fileMapping, artifact) <- artifacts) yield {
      val publishPath = Seq(
        artifact.group.replace(".", "/"),
        artifact.id,
        artifact.version
      ).mkString("/")

      val signedArtifacts = fileMapping ++ fileMapping.map {
        case (file, name) => poorMansSign(file, gpgPassphrase) -> s"$name.asc"
      }

      artifact -> signedArtifacts.flatMap {
        case (file, name) =>
          val content = read.bytes(file)

          Seq(
            name -> content,
            (name + ".md5") -> md5hex(content),
            (name + ".sha1") -> sha1hex(content)
          )
      }
    }

    val (snapshots, nonSnapshots) = mappings.partition(_._1.isSnapshot)
    if(snapshots.nonEmpty) {
      publishSnapshot(snapshots.flatMap(_._2), snapshots.map(_._1))
    }
    if(nonSnapshots.nonEmpty) {
      publishRelease(nonSnapshots.flatMap(_._2), nonSnapshots.map(_._1))
    }
  }

  private def publishSnapshot(payloads: Seq[(String, Array[Byte])],
                              artifacts: Seq[Artifact]): Unit = {

    val publishResults = payloads.map {
      case (fileName, data) =>
        log.info(s"Uploading $fileName")
        val resp = api.upload(s"$snapshotUri/$fileName", data)
        resp
    }
    reportPublishResults(publishResults, artifacts)
  }

  private def publishRelease(payloads: Seq[(String, Array[Byte])],
                             artifacts: Seq[Artifact]): Unit = {
    val profileUri = api.getStagingProfileUri(artifacts.map(_.group).mkString("-"))
    val stagingRepoId =
      api.createStagingRepo(profileUri, artifacts.map(_.group).mkString("-"))
    val baseUri = s"$uri/staging/deployByRepositoryId/$stagingRepoId/"

    val publishResults = payloads.map {
      case (fileName, data) =>
        log.info(s"Uploading ${fileName}")
        api.upload(s"$baseUri/$fileName", data)
    }
    reportPublishResults(publishResults, artifacts)

    log.info("Closing staging repository")
    api.closeStagingRepo(profileUri, stagingRepoId)

    log.info("Waiting for staging repository to close")
    awaitRepoStatus("closed", stagingRepoId)

    log.info("Promoting staging repository")
    api.promoteStagingRepo(profileUri, stagingRepoId)

    log.info("Waiting for staging repository to release")
    awaitRepoStatus("released", stagingRepoId)

    log.info("Dropping staging repository")
    api.dropStagingRepo(profileUri, stagingRepoId)

    log.info(s"Published ${artifacts.map(_.id).mkString(", ")} successfully")
  }

  private def reportPublishResults(publishResults: Seq[HttpResponse[String]],
                                   artifacts: Seq[Artifact]) = {
    if (publishResults.forall(_.is2xx)) {
      log.info(s"Published v${artifacts.map(_.id).mkString(", ")} to Sonatype")
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.code}, message: ${response.body}"
      }
      throw new RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to Sonatype. Errors: \n${errors.mkString("\n")}"
      )
    }
  }

  private def awaitRepoStatus(status: String,
                              stagingRepoId: String,
                              attempts: Int = 20): Unit = {
    def isRightStatus =
      api.getStagingRepoState(stagingRepoId).equalsIgnoreCase(status)
    var attemptsLeft = attempts

    while (attemptsLeft > 0 && !isRightStatus) {
      Thread.sleep(3000)
      attemptsLeft -= 1
      if (attemptsLeft == 0) {
        throw new RuntimeException(
          s"Couldn't wait for staging repository to be ${status}. Failing")
      }
    }
  }

  // http://central.sonatype.org/pages/working-with-pgp-signatures.html#signing-a-file
  private def poorMansSign(file: Path, passphrase: String): Path = {
    val fileName = file.toString
    import ammonite.ops.ImplicitWd._
    %("gpg", "--yes", "-a", "-b", "--batch", "--passphrase", passphrase, fileName)
    Path(fileName + ".asc")
  }

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def md5 = MessageDigest.getInstance("md5")

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

}
