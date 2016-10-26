/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.couchbase

import quasar.Predef._
import quasar.ejson, ejson._
import quasar.physical.couchbase.planner.unimplemented
import quasar.Planner.PlannerError

import argonaut.Argonaut._
import matryoshka._
import scalaz._, Scalaz._

object EJson {

  def fromCommon: Algebra[Common, String] = {
    case ejson.Arr(v)  => v.mkString("[", ", ", "]")
    case ejson.Null()  => "null"
    case ejson.Bool(v) => v.shows
    case ejson.Str(v)  => s""""$v""""
    case ejson.Dec(v)  => v.shows
  }

  def fromExtension: AlgebraM[PlannerError \/ ?, Extension, String] = {
    case ejson.Meta(v, meta) => unimplemented("ejson.Meta")
    case ejson.Map(v)        => v.toMap.asJson.nospaces.right
    case ejson.Byte(v)       => unimplemented("ejson.Byte")
    case ejson.Char(v)       => unimplemented("ejson.Char")
    case ejson.Int(v)        => v.shows.right
  }

  val fromEJson: AlgebraM[PlannerError \/ ?, EJson, String] =
    _.run.fold(fromExtension, fromCommon(_).right)

}
