//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
package edu.nrao.vlite

import akka.actor._
import akka.util.Timeout
import akka.pattern.{ ask, pipe, AskTimeoutException }
import scala.concurrent.duration._
import scala.concurrent.Future
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
  val genParams: Option[GeneratorParams] = None)
    extends Actor with ActorLogging {

  import context._

  override val supervisorStrategy = AllForOneStrategy(maxNrOfRetries = -1) {
    case _: AskTimeoutException => SupervisorStrategy.Restart
  }

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
      val gp = genParams.map {
        case SimParams(seed, filter, scale, offset) =>
          SimParams(
            seed ^ ((stationID.toLong << 48) ^ (threadID.toLong << 32)),
            filter,
            scale,
            offset)
        case f: FileParams =>
          f
      }
      actorOf(
        Generator.props(
          stationID = stationID,
          threadID = threadID,
          transporter = transporter,
          pace = pace,
          decimation = decimation,
          arraySize = arraySize,
          genParams = gp),
        s"generator-$stationID-$threadID")
  }

  protected implicit val queryTimeout = Timeout(2.seconds)

  override def preStart() {
    log.info(s"Start ${self.path}")
  }

  def receive: Receive = 
    getExpectedFrameRate orElse getBufferCount orElse {
      case start: Controller.SyncFramesTo =>
        generators foreach { g => g ! start }
      case Transporter.OpenException(msg) =>
        log.error(msg)
      case Transporter.OpenWarning(msg) =>
        log.warning(msg)
    }

  def getExpectedFrameRate: Receive = {
    case Emulator.GetExpectedFrameRate =>
      Future.traverse(generators) { g =>
        (g ? Generator.GetExpectedFrameRate) map {
          case Generator.ExpectedFrameRate(rate) => rate
        }
      } map { rates => Emulator.ExpectedFrameRate(rates.sum) } pipeTo sender
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
    genParams: Option[GeneratorParams] = None): Props =
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
      genParams)

  val defaultPace = 10.millis

  val defaultDecimation = 1

  val defaultArraySize = 5000

  case object GetExpectedFrameRate
  case class ExpectedFrameRate(framesPerSec: Int)

  object Transport extends Enumeration {
    type Transport = Value
    val Ethernet, UDP = Value
  }
}
