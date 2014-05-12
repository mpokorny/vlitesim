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
import akka.util.{ ByteString, ByteStringBuilder }

class ByteStringSource(
  val byteSourceProps: Props,
  val length: Int,
  val bufferSize: Int)
    extends ValueSourceBase[ByteString] {

  import context._

  val byteSource = actorOf(byteSourceProps, "byteSource")

  val valueRatio = (length, 1)

  val minRequestSize = 1

  def requestValues(n: Int) {
    byteSource ! ValueSource.Get(n)
  }

  var currentBuilder: Option[ByteStringBuilder] = None

  def builder(optBuilder: Option[ByteStringBuilder]):
      Option[ByteStringBuilder] = {
    if (!optBuilder.isDefined) {
      val b = ByteString.newBuilder
      b.sizeHint(length)
      Some(b)
    } else {
      optBuilder
    }
  }

  def collectByteStrings(
    acc: Vector[ByteString],
    optBuilder: Option[ByteStringBuilder],
    bs: Vector[Byte]): (Vector[ByteString], Option[ByteStringBuilder]) = {
    bs match {
      case Vector() => (acc, optBuilder)
      case _ => {
        val bldr = builder(optBuilder).get
        val (nextAcc, nextBuilder, remBs) =
          (bs splitAt (length - bldr.length)) match {
            case (bsPrefix, bsSuffix) =>
              bldr ++= bsPrefix
              if (bldr.length == length)
                (acc :+ bldr.result, None, bsSuffix)
              else
                (acc, Some(bldr), bsSuffix)
          }
        collectByteStrings(nextAcc, nextBuilder, remBs)
      }
    }
  }

  def receiveValues(as: Vector[Any]): Vector[ByteString] = {
    collectByteStrings(
      Vector.empty,
      currentBuilder,
      as.asInstanceOf[Vector[Byte]]) match {
      case (result, optBuilder) =>
        currentBuilder = optBuilder
        result
    }
  }
}

object ByteStringSource {
  def props(byteSourceProps: Props, length: Int, bufferSize: Int = 1) =
    Props(classOf[ByteStringSource], byteSourceProps, length, bufferSize)
}
