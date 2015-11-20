package quasar
package fs

import quasar.Predef._
import quasar.fp._
import quasar.fs.{Path => QPath}
import quasar.recursionschemes._, Recursive.ops._

import pathy.Path._
import scalaz._, Scalaz._
import scalaz.stream.Process

sealed trait QueryFile[A]

object QueryFile {
  final case class ExecutePlan(lp: Fix[LogicalPlan], out: AbsFile[Sandboxed])
    extends QueryFile[(PhaseResults, FileSystemError \/ ResultFile)]

  final case class Explain(lp: Fix[LogicalPlan]) extends QueryFile[PhaseResults]

  final case class ListContents(dir: AbsDir[Sandboxed])
    extends QueryFile[FileSystemError \/ Set[Node]]

  final class Ops[S[_]](implicit S0: Functor[S], S1: QueryFileF :<: S) {
    import ResultFile._

    type F[A] = Free[S, A]
    type M[A] = FileSystemErrT[F, A]

    val transforms = Transforms[F]
    import transforms._

    /** Returns the path to the result of executing the given [[LogicalPlan]],
      * using the provided path if possible.
      *
      * Execution of certain plans may return a result file other than the
      * requested file if it is more efficient to do so (i.e. to avoid copying
      * lots of data for a plan consisting of a single `ReadF(...)`).
      */
    def execute(plan: Fix[LogicalPlan], out: AbsFile[Sandboxed]): ExecM[ResultFile] =
      EitherT(WriterT(lift(ExecutePlan(plan, out))): G[FileSystemError \/ ResultFile])

    def explain(plan: Fix[LogicalPlan]): F[PhaseResults] =
      lift(Explain(plan))

    /** Returns the path to the result of executing the given [[LogicalPlan]] */
    def execute_(plan: Fix[LogicalPlan])
                (implicit M: ManageFile.Ops[S]): ExecM[ResultFile] = {

      val outFile = plan.foldMap {
        case Fix(LogicalPlan.ReadF(p)) => Vector(p)
        case _                         => Vector.empty
      }.headOption flatMap pathToAbsFile cata (M.tempFileNear, M.anyTempFile)

      for {
        out <- toExec(outFile)
        rf0 <- execute(plan, out)
        rf1 =  user.getOrModify(rf0)
                 .map(usr => if (usr == out) Temp(usr) else User(usr))
                 .merge
      } yield rf1
    }

    /** Returns the source of values from the result of executing the given
      * [[LogicalPlan]].
      */
    def evaluate(plan: Fix[LogicalPlan])
                (implicit R: ReadFile.Ops[S], M: ManageFile.Ops[S])
                : Process[ExecM, Data] = {

      val hoistFS: FileSystemErrT[F, ?] ~> ExecM =
        Hoist[FileSystemErrT].hoist[F, G](liftMT[F, PhaseResultT])

      def values(f: AbsFile[Sandboxed]) =
        R.scanAll(f).translate[ExecM](hoistFS)

      def handleTemp(tmp: AbsFile[Sandboxed]) = {
        val cleanup = (hoistFS(M.deleteFile(tmp)): ExecM[Unit])
                        .liftM[Process].drain
        values(tmp) onComplete cleanup
      }

      execute_(plan).liftM[Process] flatMap {
        case Case.User(f) => values(f)
        case Case.Temp(f) => handleTemp(f)
      }
    }

    /** Returns the path to the result of executing the given SQL^2 query
      * using the given output file if possible.
      */
    def executeQuery(query: sql.Expr, vars: Variables, out: AbsFile[Sandboxed])
                    : CompExecM[ResultFile] = {

      compileAnd(query, vars)(execute(_, out))
    }

    /** Returns the path to the result of executing the given SQL^2 query. */
    def executeQuery_(query: sql.Expr, vars: Variables)
                     (implicit M: ManageFile.Ops[S]): CompExecM[ResultFile] = {
      compileAnd(query, vars)(execute_)
    }

    /** Returns the source of values from the result of executing the given
      * SQL^2 query.
      */
    def evaluateQuery(query: sql.Expr, vars: Variables)
                     (implicit R: ReadFile.Ops[S], M: ManageFile.Ops[S])
                     : Process[CompExecM, Data] = {

      def comp = compToCompExec(queryPlan(query, vars)).liftM[Process]

      comp flatMap (lp => evaluate(lp).translate[CompExecM](execToCompExec))
    }

    def explainQuery(query: sql.Expr, vars: Variables): SemanticErrsT[F,PhaseResults] = {
      val writer = queryPlan(query, vars).run
      val phases = writer.written
      EitherT[F,SemanticErrors,PhaseResults](
        writer.value.map(lp => explain(lp).map(physicalPhases => phases ++ physicalPhases)).sequenceU)
    }

    /** Returns immediate children of the given directory, fails if the
      * directory does not exist.
      */
    def ls(dir: AbsDir[Sandboxed]): M[Set[Node]] =
      EitherT(lift(ListContents(dir)))

    /** The children of the root directory. */
    def ls: M[Set[Node]] =
      ls(rootDir)

    /** Returns all files in this directory and all of it's sub-directories
      * Fails if the directory does not exist.
      */
    def descendantFiles(dir: AbsDir[Sandboxed]): M[Set[RelFile[Sandboxed]]] = {
      type S[A] = StreamT[M, A]

      def lsR(desc: RelDir[Sandboxed]): StreamT[M, RelFile[Sandboxed]] =
        StreamT.fromStream[M, Node](ls(dir </> desc) map (_.toStream))
          .flatMap(_.path.fold(
            d => lsR(desc </> d),
            f => (desc </> f).point[S]))

      lsR(currentDir).foldLeft(Set.empty[RelFile[Sandboxed]])(_ + _)
    }

    /** Returns whether the given file exists. */
    def fileExists(file: AbsFile[Sandboxed]): F[Boolean] = {
      // TODO: Add fileParent[B, S](f: Path[B, File, S]): Path[B, Dir, S] to pathy
      val parent =
        parentDir(file) getOrElse scala.sys.error("impossible, files have parents!")

      ls(parent)
        .map(_ flatMap (_.file.map(parent </> _).toSet) exists (identicalPath(file, _)))
        .getOrElse(false)
    }

    ////

    private def compileAnd[A](query: sql.Expr, vars: Variables)
                             (f: Fix[LogicalPlan] => ExecM[A])
                             : CompExecM[A] = {
      compToCompExec(queryPlan(query, vars))
        .flatMap(lp => execToCompExec(f(lp)))
    }

    private def pathToAbsFile(p: QPath): Option[AbsFile[Sandboxed]] =
      p.file map (fn =>
        p.asAbsolute.dir
          .foldLeft(rootDir[Sandboxed])((d, n) => d </> dir(n.value)) </>
          file(fn.value))

    private def lift[A](qf: QueryFile[A]): F[A] =
      Free.liftF(S1.inj(Coyoneda.lift(qf)))
  }

  object Ops {
    implicit def apply[S[_]](implicit S0: Functor[S], S1: QueryFileF :<: S): Ops[S] =
      new Ops[S]
  }

  class Transforms[F[_]: Monad] {
    type G[A] = PhaseResultT[F, A]
    type H[A] = SemanticErrsT[G, A]

    type ExecM[A]     = FileSystemErrT[G, A]
    type CompExecM[A] = FileSystemErrT[H, A]

    val execToCompExec: ExecM ~> CompExecM =
      Hoist[FileSystemErrT].hoist[G, H](liftMT[G, SemanticErrsT])

    val compToCompExec: CompileM ~> CompExecM = {
      val hoistW: PhaseResultW ~> G = Hoist[PhaseResultT].hoist(pointNT[F])
      val hoistC: CompileM ~> H     = Hoist[SemanticErrsT].hoist(hoistW)
      liftMT[H, FileSystemErrT] compose hoistC
    }

    val toExec: F ~> ExecM =
      liftMT[G, FileSystemErrT] compose liftMT[F, PhaseResultT]

    val toCompExec: F ~> CompExecM =
      execToCompExec compose toExec
  }

  object Transforms {
    def apply[F[_]: Monad]: Transforms[F] =
      new Transforms[F]
  }
}
