package edu.nrao.vlite

import akka.actor._
import scala.concurrent.duration._
import scala.collection.mutable

final class Emulator(
  val device: String,
  val destination: MAC,
  val sourceIDs: Seq[(Int, Int)],
  val pace: FiniteDuration = Emulator.defaultPace,
  val decimation: Int = Emulator.defaultDecimation)
    extends Actor {

  import context._

  override def supervisorStrategy = {
    import akka.actor.SupervisorStrategy._

    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1.minute) {
      case _ => Restart
    }
  }

  val transporter =
    actorOf(EthernetTransporter.props(device, destination), "transporter")

  val generators = sourceIDs map {
    case (stationID, threadID) =>
      actorOf(Generator.props(
        stationID = stationID,
        threadID = threadID,
        transporter = transporter,
        pace = pace,
        decimation = decimation))
  }

  def receive: Receive = idle

  def idle: Receive = queries orElse {
    case Emulator.Start =>
      for (g <- generators) g ! Generator.Start
      transporter ! Transporter.Start
      become(running)
    case _ =>
  }

  def running: Receive = queries orElse {
    case Emulator.Stop =>
      transporter ! Transporter.Stop
      for (g <- generators) g ! Generator.Stop
      become(idle)
    case _ =>
  }

  def queries = getGenLatencies orElse getBufferCount

  private val latencies = mutable.Seq.fill(generators.length)(-1)

  private var latencyRequesters = Vector.empty[ActorRef]

  private def haveAllLatencies = latencies forall (_ != -1)

  private def resetLatencies {
    for (i <- 0 until latencies.length) latencies(i) = -1
  }

  def getGenLatencies: Receive = {
    case Emulator.GetGeneratorLatencies =>
      if (latencyRequesters.isEmpty)
        for (g <- generators) g ! Generator.GetLatency
      latencyRequesters = latencyRequesters :+ sender
    case Generator.Latency(l) =>
      latencies(latencies.indexOf(sender)) = l
      if (haveAllLatencies) {
        val values = sourceIDs.zip(latencies).toMap
        for (r <- latencyRequesters) r ! Emulator.Latencies(values)
        latencyRequesters = Vector.empty
        resetLatencies
      }
  }

  private var bufferCountRequesters = Vector.empty[ActorRef]

  def getBufferCount: Receive = {
    case Transporter.GetBufferCount =>
      if (bufferCountRequesters.isEmpty)
        transporter ! Transporter.GetBufferCount
      bufferCountRequesters = bufferCountRequesters :+ sender
    case count: Transporter.BufferCount =>
      for (r <- bufferCountRequesters) r ! count
      bufferCountRequesters = Vector.empty
  }
}

object Emulator {
  def props(
    device: String,
    destination: MAC,
    sourceIDs: Seq[(Int, Int)],
    pace: FiniteDuration = defaultPace,
    decimation: Int = defaultDecimation): Props =
    Props(classOf[Emulator], device, destination, sourceIDs, pace, decimation)

  val defaultPace = 1.milli

  val defaultDecimation = 1

  case object Start
  case object Stop
  case object GetGeneratorLatencies
  case class Latencies(values: Map[(Int, Int), Int])
}
