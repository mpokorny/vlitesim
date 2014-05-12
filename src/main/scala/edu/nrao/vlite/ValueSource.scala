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

import scala.annotation.tailrec
import akka.actor._
import akka.util.ByteString
import java.io.{ File, FileInputStream }
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

abstract class ValueSourceBase[V] extends Actor {

  val bufferSize: Int

  var endOfStream: Boolean = false

  def requestValues(n: Int): Unit

  def receiveValues(as: Vector[Any]): Vector[V]

  val valueRatio: (Int, Int) // this actor's request to value ratio

  val minRequestSize: Int

  private var buffer: Vector[V] = Vector.empty

  private var pendingGets: Vector[(ActorRef, Int)] = Vector.empty

  private var pendingReceives: Int = 0

  final protected def ceil(n: Int, d: Int) =
    (n + (d - n % d) % d) / d

  private def requestFullBuffer() {
    val eventualLength = buffer.length + pendingReceives
    val deficit = bufferSize - eventualLength
    if (deficit > 0) {
      val nr = numRequests(deficit)
      requestValues(nr)
      valueRatio match {
        case (r, v) => pendingReceives += (nr * v) / r
      }
    }
  }

  private def numRequests(numValues: Int) = 
    if (numValues > 0)
      (valueRatio match {
        case (r, v) => ceil(numValues, v) * r
      }) max minRequestSize
    else
      0

  private def sendToPendingGets() {
    pendingGets match {
      case (actor, numValues) +: ps =>
        if (buffer.length >= numValues) {
          pendingGets = ps
          fulfillRequest(actor, numValues)
          sendToPendingGets()
        } else {
          if (!endOfStream) {
            requestFullBuffer()
          }
          else {
            pendingGets = ps
            if (buffer.isEmpty) {
              actor ! ValueSource.EndOfStream
            } else {
              actor ! ValueSource.Values(buffer)
              buffer = Vector.empty
            }
            sendToPendingGets()
          }
        }
      case Vector() =>
        if (!endOfStream) requestFullBuffer()
    }
  }

  private def fulfillRequest(to: ActorRef, n: Int) {
    if (n > 0)
      buffer.splitAt(n) match {
        case (vs, rem) =>
          to ! ValueSource.Values(vs)
          buffer = rem
      }
    else
      to ! ValueSource.Values(Vector.empty[V])
  }

  private def addPendingGet(to: ActorRef, numValues: Int) {
    val nv = numValues max 0
    if (!endOfStream) {
      val nr = numRequests(nv)
      requestValues(nr)
      valueRatio match {
        case (r, v) => pendingReceives += (nr * v) / r
      }
    }
    pendingGets = pendingGets :+ ((to, nv))
  }

  override def preStart() {
    requestFullBuffer()
  }

  def receive: Receive = {
    case ValueSource.Get(n) =>
      addPendingGet(sender, n)
      sendToPendingGets()
    case ValueSource.Values(vs) =>
      assert(!endOfStream)
      val newVs = receiveValues(vs)
      pendingReceives -= newVs.length
      buffer ++= newVs
      sendToPendingGets()
    case ValueSource.EndOfStream =>
      endOfStream = true
      sendToPendingGets()
      assert(pendingGets.isEmpty)
  }
}

class ValueSource[V](sourceProps: Props, val bufferSize: Int)
    extends ValueSourceBase[V] {

  val valueRatio = (1, 1)

  val minRequestSize = 1

  val source = context.actorOf(sourceProps, "source")

  def requestValues(n: Int) {
    source ! ValueSource.Get(n)
  }

  def receiveValues(as: Vector[Any]): Vector[V] =
    as.asInstanceOf[Vector[V]]
}

final class FileByteSource(
  val file: File,
  val length: Int,
  val cycleData: Boolean,
  val bufferSize: Int)
    extends ValueSourceBase[ByteString] with ActorLogging {

  val valueRatio = (1, 1)

  val minRequestSize = 1

  val mappedFile = {
    val channel = new FileInputStream(file).getChannel
    val result = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size)
    channel.close()
    result
  }

  var endOfFile = false

  @tailrec
  def getValues(n: Int, acc: Vector[ByteString]): Vector[ByteString] = {
    if (n == 0 || endOfFile) {
      acc
    } else if (mappedFile.remaining < length && !cycleData) {
      endOfFile = true
      acc
    } else {
      val next =
        if (mappedFile.remaining >= length) {
          val buf = mappedFile.slice
          buf.limit(length)
          mappedFile.position(mappedFile.position + length)
          ByteString(buf)
        } else {
          val nextVs =
            if (mappedFile.remaining > 0)
              Some(ByteString(mappedFile.slice))
            else
              None
          val remLength = length - mappedFile.remaining
          mappedFile.position(0)
          val buf = mappedFile.slice
          buf.limit(remLength)
          val remVs = ByteString(buf)
          mappedFile.position(remLength)
          nextVs.map(_ ++ remVs).getOrElse(remVs)
        }
      getValues(n - 1, acc :+ next)
    }
  }

  def requestValues(n: Int) {
    if (!endOfFile) {
      val vs = getValues(n, Vector.empty)
      if (n == 0 || vs.length > 0)
        self ! ValueSource.Values(vs)
      else
        self ! ValueSource.EndOfStream
    } else {
      self ! ValueSource.EndOfStream
    }
  }

  def receiveValues(as: Vector[Any]): Vector[ByteString] =
    as.asInstanceOf[Vector[ByteString]]
}

object ValueSource {
  def props[V](generate: () => V, bufferSize: Int): Props =
    props(Props(classOf[Getter[V]], generate), bufferSize)

  def props[V](sourceProps: Props, bufferSize: Int): Props =
    Props(classOf[ValueSource[V]], sourceProps, bufferSize)

  case class Get(n: Int)
  case class Values[V](v: Vector[V])
  case object EndOfStream

  object EndOfStreamException extends Exception

  private class Getter[V](generate: () => V) extends Actor {
    import context._

    var endOfStream = false

    def receive: Receive = {
      case ValueSource.Get(n) =>
        if (!endOfStream) {
          try {
            sender ! Values((0 until n).toVector map (_ => generate()))
          } catch {
            case EndOfStreamException =>
              endOfStream = true
              sender ! ValueSource.EndOfStream
          }
        } else {
          sender ! ValueSource.EndOfStream
        }
    }
  }
}

object FileByteSource {
  def props(
    file: File,
    length: Int,
    cycleData: Boolean,
    bufferSize: Int = 1): Props =
    Props(
      classOf[FileByteSource],
      file,
      length,
      cycleData,
      bufferSize)
}
