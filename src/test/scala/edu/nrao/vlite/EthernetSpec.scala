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

import akka.util.{ ByteString, ByteStringBuilder }
import akka.io._
import org.scalatest._
import java.nio.ByteOrder

class EthernetSpec extends FlatSpec with Matchers {

  final case class TestPayload(val int: Int, val float: Float, val short: Short)
      extends HasEtherCode {
    def etherCode = 88

    override def equals(other: Any) = other match {
      case TestPayload(tInt, tFloat, tShort) =>
        tInt == int && tFloat == float && tShort == short
      case _ =>
        false
    }

    override def hashCode =
      41 * (
        41 * (
          41 + int
        ) + float.hashCode
      ) + short

    override def toString = s"TestPayload($int,$float,$short)"
  }

  object TestPayloadStage
      extends SymmetricPipelineStage[EthernetContext, TestPayload, ByteString] {

    implicit val byteOrder = ByteOrder.BIG_ENDIAN

    override def apply(ctx: EthernetContext) =
      new SymmetricPipePair[TestPayload, ByteString] {
        
        def commandPipeline = { payload: TestPayload =>
          val bb = ByteString.newBuilder
          bb.putInt(payload.int).putFloat(payload.float).putShort(payload.short)
          ctx.singleCommand(bb.result)
        }

        def eventPipeline = { bs: ByteString =>
          val iter = bs.iterator
          val int = iter.getInt
          val float = iter.getFloat
          val short = iter.getShort
          ctx.singleEvent(TestPayload(int, float, short))
        }
      }
  }

  object EthernetContext extends EthernetContext {
    def withEthCRC(bs: ByteString)(implicit byteOrder: ByteOrder): ByteString =
      bs
  }

  object TestStage extends EthernetStage(TestPayloadStage)

  lazy val PipelinePorts(pipelinePort, eventPort, _) =
    PipelineFactory.buildFunctionTriple(EthernetContext, TestStage)

  "An Ethernet frame" should
    "encode and decode a test payload" in {
      val t = TestPayload(4, 3.21f, 1)
      val src = MAC(0,1,2,3,4,5)
      val dst = MAC(10,11,12,13,14,15)
      val eth = Ethernet(source=src, destination=dst, payload=t)
      val bin = pipelinePort(eth)._2.head
      val eth1 = eventPort(bin)._1.head
      eth should === (eth1)
  }

}
