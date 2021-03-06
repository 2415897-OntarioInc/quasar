/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.fs

import scala.Predef.$conforms
import slamdata.Predef._
import quasar._, DataArbitrary._
import quasar.contrib.pathy._
import quasar.fs.InMemory.InMemState

import org.scalacheck.Arbitrary, Arbitrary.{arbitrary => arb}
import pathy.scalacheck.PathyArbitrary._

trait InMemoryArbitrary {
  implicit val arbitraryInMemState: Arbitrary[InMemState] =
    Arbitrary(arb[Map[AFile, Vector[Data]]].map(InMemState(0, _, Map(), Map(), Map(), Map())))
}

object InMemoryArbitrary extends InMemoryArbitrary
