package edu.nrao.vlite

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import akka.remote.RemoteScope
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{ Success, Failure }

class Controller extends Actor with ActorLogging {
  import context._

  val settings = Settings(context.system)

  protected def emulatorActor(instance: EmulatorInstance, index: Int):
      ActorRef =
    actorOf(Emulator.props(
      device = instance.device,
      destination = instance.destination,
      hostname = instance.hostname,
      transport = instance.transport,
      framing = instance.framing,
      sourceIDs = instance.threadIDs map (tid => (instance.stationID, tid)),
      pace = instance.pace,
      decimation = instance.decimation,
      arraySize = instance.arraySize,
      simParams = instance.simParams).
      withDeploy(Deploy(
        scope = RemoteScope(settings.remoteAddress(instance.hostname)))),
      s"emulator${index}")

  val emulators: Seq[EmulatorInfo] =
    settings.emulatorInstances.zipWithIndex map {
      case (instance, idx) =>
        EmulatorInfo(instance, emulatorActor(instance, idx))
    }

  implicit val queryTimeout = Timeout(1.second)

  var expectedFrameRates: Option[Vector[Int]] = None

  var recentBufferCounts: Vector[Vector[Long]] = Vector.empty

  case object GetExpectedFrameRates
  case class ExpectedFrameRates(rates: Vector[Int])
  case object GetBufferCounts
  case class BufferCounts(counts: Vector[Long])

  def getExpectedFrameRates {
    Future.traverse(emulators) { em =>
      (em.actorRef ? Emulator.GetExpectedFrameRate) map {
        case Emulator.ExpectedFrameRate(rate) => rate
        case _ => -1
      }
    } onComplete {
      case Success(rates) if rates.forall(_ >= 0) =>
        self ! ExpectedFrameRates(rates.toVector)
      case _ =>
        self ! GetExpectedFrameRates
    }
  }

  def compareToExpected(numSec: Int, start: Vector[Long], end: Vector[Long]) {
    expectedFrameRates map { expected =>
      val rateErrors =
        start zip end map {
          case (init, fin) => fin - init
        } zip expected map {
          case (actual, expected) =>
            val expectedOverInterval = numSec * expected
            (actual - expectedOverInterval).toDouble / expectedOverInterval
        }
      val slowEmulators = (List.empty[Int] /: rateErrors.zipWithIndex) {
        case (acc, (err, i)) if err < -0.1 => i :: acc
        case (acc, _) => acc
      }
      if (slowEmulators.length > 0) {
        log.warning(
          "Slow frame generation rate from emulator{} {}",
          if (slowEmulators.length > 1) "s" else "",
          slowEmulators.reverse.mkString(","))
        if (rateErrors exists (_ < -0.5)) {
          log.error("Frame rate too low! Exiting...")
          system.shutdown()
        }
      }
    }
  }

  override def preStart() {
    log.info(s"Start ${self.path}")
    system.scheduler.schedule(
      1.second,
      1.second,
      self,
      GetBufferCounts
    )
    getExpectedFrameRates
  }

  def receive: Receive = {
    case GetBufferCounts =>
      Future.traverse(emulators) { em =>
        (em.actorRef ? Transporter.GetBufferCount) map {
          case Transporter.BufferCount(count) => count
          case _ => 0L
        } recover {
          case _: Throwable => 0L
        }
      } onSuccess {
        case counts => self ! BufferCounts(counts.toVector)
      }
    case BufferCounts(counts: Vector[Long]) =>
      log.info("BufferCounts({})", counts.mkString(","))
      recentBufferCounts = recentBufferCounts :+ counts
      if (recentBufferCounts.length >= 6) {
        recentBufferCounts.takeRight(2) match {
          case Vector(start, end) => compareToExpected(1, start, end)
        }
        recentBufferCounts = recentBufferCounts.tail
      }
    case ExpectedFrameRates(rates) =>
      expectedFrameRates = Some(rates)
      log.info("ExpectedFrameRates({})", rates.mkString(","))
  }
}

case class EmulatorInfo(
  instance: EmulatorInstance,
  actorRef: ActorRef)
