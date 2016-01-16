package quasar.fs

import quasar.Predef._
import quasar.fs.SandboxedPathy._

import org.specs2.mutable._
import org.specs2.ScalaCheck
import org.specs2.scalaz._
import pathy.Path._
import pathy.scalacheck.PathyArbitrary._
import scalaz._, Scalaz._

class SandboxedPathySpec extends Specification with DisjunctionMatchers with ScalaCheck with FileSystemFixture {

  "rootSubPath" should {
    "returns the correct sub path" ! prop { (d: ADir, p: RPath) =>
      refineType(rootSubPath(depth(d), d </> p)) ==== d.left
    }

    "return the path if the index is too long or the same" ! prop { (d: ADir, i: Int) =>
      (i > 0 && (i.toLong + depth(d)) < Int.MaxValue) ==> {
        refineType(rootSubPath(depth(d) + i, d)) ==== d.left
      }
    }
  }

  "largestCommonPathFromRoot" should {
    "completely different APaths should return rootDir" ! prop { (a: APath, b: APath) =>
      segAt(0, a) =/= segAt(0, b) ==> {
        refineType(largestCommonPathFromRoot(a, b)) ==== rootDir.left
      }
    }

    "return common path" ! prop { (a: ADir, b: RPath, c: RPath) =>
      segAt(0, b) =/= segAt(0, c) ==> {
        refineType(largestCommonPathFromRoot(a </> b, a </> c)) ==== a.left
      }
    }
  }

  "segAt" should {
    "segment at specified index" ! prop { (d: ADir, dirName: String, p: RPath) =>
      segAt(depth(d), d </> dir(dirName) </> p) ==== DirName(dirName).right.some
    }
  }

}
