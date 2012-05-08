/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package daze

import akka.actor.ActorSystem
import akka.dispatch._
import akka.util.Timeout
import akka.util.duration._

import blueeyes.json.JPath
import blueeyes.json.JsonAST._
import blueeyes.json.JsonParser

import com.precog.common._
import com.precog.common.security._
import com.precog.common.util._
import com.precog.yggdrasil._
import com.precog.yggdrasil.actor._
import com.precog.yggdrasil.metadata._
import com.precog.yggdrasil.util._
import com.precog.util._
import SValue._

import scalaz.effect._

import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap
import org.specs2.mutable._

trait StubYggShardComponent extends YggShardComponent {
  type Dataset[α]

  def actorSystem: ActorSystem 
  implicit def asyncContext: ExecutionContext

  val dataPath = Path("/test")
  def sampleSize: Int
  def dataset(idCount: Int, data: Iterable[(Identities, Seq[CValue])]): Dataset[Seq[CValue]]

  trait Storage extends YggShard[Dataset] {
    implicit val ordering = IdentitiesOrder.toScalaOrdering
    def routingTable: RoutingTable = new SingleColumnProjectionRoutingTable

    case class DummyProjection(descriptor: ProjectionDescriptor, data: SortedMap[Identities, Seq[CValue]]) extends Projection[Dataset] {
      val chunkSize = 2000

      def + (row: (Identities, Seq[CValue])) = copy(data = data + row)

      def getAllPairs(expiresAt: Long): Dataset[Seq[CValue]] = dataset(1, data)
    }

    lazy val sampleData: Vector[JValue] = DistributedSampleSet.sample(sampleSize, 0)._1

    val projections: Map[ProjectionDescriptor, Projection[Dataset]] = sampleData.zipWithIndex.foldLeft(Map.empty[ProjectionDescriptor, DummyProjection]) { 
      case (acc, (jobj, i)) => routingTable.route(EventMessage(EventId(0, i), Event(dataPath, "", jobj, Map()))).foldLeft(acc) {
        case (acc, ProjectionData(descriptor, identities, values, _)) =>
          acc + (descriptor -> (acc.getOrElse(descriptor, DummyProjection(descriptor, new TreeMap())) + ((identities, values))))
      }
    }

    def storeBatch(ems: Seq[EventMessage], timeout: Timeout) = sys.error("Feature not implemented in test stub.")

    def projectionMetadata: Map[ProjectionDescriptor, ColumnMetadata] = 
      projections.keys.map(pd => (pd, ColumnMetadata.Empty)).toMap

    def metadata = {
      val localMetadata = new LocalMetadata(projectionMetadata, VectorClock.empty)
      localMetadata.toStorageMetadata(actorSystem.dispatcher)
    }

    def userMetadataView(uid: String) = new UserMetadataView(uid, new UnlimitedAccessControl(), metadata)(actorSystem.dispatcher)

    def projection(descriptor: ProjectionDescriptor, timeout: Timeout): Future[(Projection[Dataset], Release)] =
      Future((projections(descriptor), new Release(IO(()))))
  }
}



// vim: set ts=4 sw=4 et:
