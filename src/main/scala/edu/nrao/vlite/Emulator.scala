package edu.nrao.vlite

import akka.actor._
import akka.util.Timeout
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import scala.collection.mutable
import scala.util.Try
import java.net.InetSocketAddress

final class Emulator(
  val transport: Emulator.Transport.Transport,
  val framing: Option[EthernetTransporter.Framing.Framing],
  val device: Option[String],
  val hostname: String,
  val destination: (String, String),
  val sourceIDs: Seq[(Int, Int)],
  val pace: FiniteDuration = Emulator.defaultPace,
  val decimation: Int = Emulator.defaultDecimation,
  val arraySize: Int = Emulator.defaultArraySize,
  val simParams: Option[SimParams] = None)
    extends Actor with ActorLogging {

  import context._

  val transporter = {
    val dstSock =
      (Try {
        val hostAndPort = destination._1.split(':')
        val hostname = hostAndPort(0)
        val port = hostAndPort(1).toInt
        new InetSocketAddress(hostname, port)
      }).toOption
    transport match {
      case Emulator.Transport.Ethernet => {
        val srcSock = Some(new InetSocketAddress(hostname, 5555))
        actorOf(
          EthernetTransporter.props(
            device.get, MAC(destination._2), dstSock, srcSock, framing.get),
          "transporter")
      }
      case Emulator.Transport.UDP => {
        actorOf(UdpTransporter.props(dstSock.get), "transporter")
      }
    }
  }

  val generators = sourceIDs map {
    case (stationID, threadID) =>
      val sp = simParams.map {
        case SimParams(seed, filter, scale, offset, numRngThreads) =>
          SimParams(
            seed ^ ((stationID.toLong << 48) ^ (threadID.toLong << 32)),
            filter,
            scale,
            offset,
            numRngThreads)
      }
      actorOf(Generator.props(
        stationID = stationID,
        threadID = threadID,
        transporter = transporter,
        pace = pace,
        decimation = decimation,
        arraySize = arraySize,
        simParams = sp))
  }

  protected implicit val queryTimeout = Timeout(1.seconds)

  override def preStart() {
    println(s"Start ${self.path}")
  }

  def receive: Receive = 
    getGenLatencies orElse getBufferCount orElse {
      case Transporter.OpenException(msg) =>
        log.error(msg)
      case Transporter.OpenWarning(msg) =>
        log.warning(msg)
    }

  private val latencies = mutable.Seq.fill(generators.length)(-1)

  private var latencyRequesters = Vector.empty[ActorRef]

  private var latencyTimeout: Option[Cancellable] = None

  private def haveAllLatencies = latencies forall (_ != -1)

  private def resetLatencies() {
    for (i <- 0 until latencies.length) latencies(i) = -1
    latencyRequesters = Vector.empty
    latencyTimeout foreach (_.cancel)
    latencyTimeout = None
  }

  private def sendLatencyResponses() {
    val values = (sourceIDs.zip(latencies).filter {
      case (_, -1) => false
      case _ => true
    }).toMap
    for (r <- latencyRequesters) r ! Emulator.Latencies(values)
    resetLatencies()
  }

  def getGenLatencies: Receive = {
    case Emulator.GetGeneratorLatencies =>
      if (latencyRequesters.isEmpty) {
        for (g <- generators) g ! Generator.GetLatency
        latencyTimeout = Some(system.scheduler.scheduleOnce(
          queryTimeout.duration,
          self,
          Emulator.LatenciesTimeout))
      }
      latencyRequesters = latencyRequesters :+ sender
    case Generator.Latency(l) =>
      latencies(generators.indexOf(sender)) = l
      if (haveAllLatencies)
        sendLatencyResponses()
    case Emulator.LatenciesTimeout =>
      sendLatencyResponses()
  }

  def getBufferCount: Receive = {
    case Transporter.GetBufferCount => {
      (transporter ? Transporter.GetBufferCount) pipeTo sender
    }
  }
}

object Emulator {
  def props(
    transport: Transport.Transport,
    framing: Option[EthernetTransporter.Framing.Framing],
    device: Option[String],
    hostname: String,
    destination: (String, String),
    sourceIDs: Seq[(Int, Int)],
    pace: FiniteDuration = defaultPace,
    decimation: Int = defaultDecimation,
    arraySize: Int = defaultArraySize,
    simParams: Option[SimParams] = None): Props =
    Props(
      classOf[Emulator],
      transport,
      framing,
      device,
      hostname,
      destination,
      sourceIDs,
      pace,
      decimation,
      arraySize,
      simParams)

  val defaultPace = 1.milli

  val defaultDecimation = 1

  val defaultArraySize = 5000

  case object GetGeneratorLatencies
  case class Latencies(values: Map[(Int, Int), Int])
  case object LatenciesTimeout
  case object WasRunning

  object Transport extends Enumeration {
    type Transport = Value
    val Ethernet, UDP = Value
  }
}
