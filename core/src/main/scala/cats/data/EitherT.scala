package cats
package data

import cats.functor.Bifunctor
import cats.instances.either._
import cats.syntax.either._

/**
 * Transformer for `Either`, allowing the effect of an arbitrary type constructor `F` to be combined with the
 * fail-fast effect of `Either`.
 *
 * `EitherT[F, A, B]` wraps a value of type `F[Either[A, B]]`. An `F[C]` can be lifted in to `EitherT[F, A, C]` via `EitherT.right`,
 * and lifted in to a `EitherT[F, C, B]` via `EitherT.left`.
 */
final case class EitherT[F[_], A, B](value: F[Either[A, B]]) {
  def fold[C](fa: A => C, fb: B => C)(implicit F: Functor[F]): F[C] = F.map(value)(_.fold(fa, fb))

  def isLeft(implicit F: Functor[F]): F[Boolean] = F.map(value)(_.isLeft)

  def isRight(implicit F: Functor[F]): F[Boolean] = F.map(value)(_.isRight)

  def swap(implicit F: Functor[F]): EitherT[F, B, A] = EitherT(F.map(value)(_.swap))

  def getOrElse(default: => B)(implicit F: Functor[F]): F[B] = F.map(value)(_.getOrElse(default))

  def getOrElseF(default: => F[B])(implicit F: Monad[F]): F[B] = {
    F.flatMap(value) {
      case Left(_) => default
      case Right(b) => F.pure(b)
    }
  }

  def orElse(default: => EitherT[F, A, B])(implicit F: Monad[F]): EitherT[F, A, B] = {
    EitherT(F.flatMap(value) {
      case Left(_) => default.value
      case r @ Right(_) => F.pure(r)
    })
  }

  def recover(pf: PartialFunction[A, B])(implicit F: Functor[F]): EitherT[F, A, B] =
    EitherT(F.map(value)(_.recover(pf)))

  def recoverWith(pf: PartialFunction[A, EitherT[F, A, B]])(implicit F: Monad[F]): EitherT[F, A, B] =
    EitherT(F.flatMap(value) {
      case Left(a) if pf.isDefinedAt(a) => pf(a).value
      case other => F.pure(other)
    })

  def valueOr(f: A => B)(implicit F: Functor[F]): F[B] = fold(f, identity)

  def forall(f: B => Boolean)(implicit F: Functor[F]): F[Boolean] = F.map(value)(_.forall(f))

  def exists(f: B => Boolean)(implicit F: Functor[F]): F[Boolean] = F.map(value)(_.exists(f))

  def ensure(onFailure: => A)(f: B => Boolean)(implicit F: Functor[F]): EitherT[F, A, B] = EitherT(F.map(value)(_.ensure(onFailure)(f)))

  def toOption(implicit F: Functor[F]): OptionT[F, B] = OptionT(F.map(value)(_.toOption))

  def to[G[_]](implicit F: Functor[F], G: Alternative[G]): F[G[B]] =
    F.map(value)(_.to[G])

  def collectRight(implicit F: MonadCombine[F]): F[B] =
    F.flatMap(value)(_.to[F])

  def bimap[C, D](fa: A => C, fb: B => D)(implicit F: Functor[F]): EitherT[F, C, D] = EitherT(F.map(value)(_.bimap(fa, fb)))

  def bitraverse[G[_], C, D](f: A => G[C], g: B => G[D])(implicit traverseF: Traverse[F], applicativeG: Applicative[G]): G[EitherT[F, C, D]] =
    applicativeG.map(traverseF.traverse(value)(axb => Bitraverse[Either].bitraverse(axb)(f, g)))(EitherT.apply)

  def applyAlt[D](ff: EitherT[F, A, B => D])(implicit F: Apply[F]): EitherT[F, A, D] =
    EitherT[F, A, D](F.map2(this.value, ff.value)((xb, xbd) => Apply[Either[A, ?]].ap(xbd)(xb)))

  def flatMap[D](f: B => EitherT[F, A, D])(implicit F: Monad[F]): EitherT[F, A, D] =
    EitherT(F.flatMap(value) {
      case l @ Left(_) => F.pure(l.rightCast)
      case Right(b) => f(b).value
    })

  def flatMapF[D](f: B => F[Either[A, D]])(implicit F: Monad[F]): EitherT[F, A, D] =
    flatMap(f andThen EitherT.apply)

  def transform[C, D](f: Either[A, B] => Either[C, D])(implicit F: Functor[F]): EitherT[F, C, D] =
    EitherT(F.map(value)(f))

  def subflatMap[D](f: B => Either[A, D])(implicit F: Functor[F]): EitherT[F, A, D] =
    transform(_.flatMap(f))

  def map[D](f: B => D)(implicit F: Functor[F]): EitherT[F, A, D] = bimap(identity, f)

  def semiflatMap[D](f: B => F[D])(implicit F: Monad[F]): EitherT[F, A, D] =
    flatMap(b => EitherT.right[F, A, D](f(b)))

  def leftMap[C](f: A => C)(implicit F: Functor[F]): EitherT[F, C, B] = bimap(f, identity)

  def compare(that: EitherT[F, A, B])(implicit o: Order[F[Either[A, B]]]): Int =
    o.compare(value, that.value)

  def partialCompare(that: EitherT[F, A, B])(implicit p: PartialOrder[F[Either[A, B]]]): Double =
    p.partialCompare(value, that.value)

  def ===(that: EitherT[F, A, B])(implicit eq: Eq[F[Either[A, B]]]): Boolean =
    eq.eqv(value, that.value)

  def traverse[G[_], D](f: B => G[D])(implicit traverseF: Traverse[F], applicativeG: Applicative[G]): G[EitherT[F, A, D]] =
    applicativeG.map(traverseF.traverse(value)(axb => Traverse[Either[A, ?]].traverse(axb)(f)))(EitherT.apply)

  def foldLeft[C](c: C)(f: (C, B) => C)(implicit F: Foldable[F]): C =
    F.foldLeft(value, c)((c, axb) => axb.foldLeft(c)(f))

  def foldRight[C](lc: Eval[C])(f: (B, Eval[C]) => Eval[C])(implicit F: Foldable[F]): Eval[C] =
    F.foldRight(value, lc)((axb, lc) => axb.foldRight(lc)(f))

  def merge(implicit ev: B <:< A, F: Functor[F]): F[A] = F.map(value)(_.fold(identity, ev.apply))

  /**
   * Similar to `Either#combine` but mapped over an `F` context.
   *
   * Examples:
   * {{{
   * scala> import cats.data.EitherT
   * scala> import cats.implicits._
   * scala> val l1: EitherT[Option, String, Int] = EitherT.left(Some("error 1"))
   * scala> val l2: EitherT[Option, String, Int] = EitherT.left(Some("error 2"))
   * scala> val r3: EitherT[Option, String, Int] = EitherT.right(Some(3))
   * scala> val r4: EitherT[Option, String, Int] = EitherT.right(Some(4))
   * scala> val noneEitherT: EitherT[Option, String, Int] = EitherT.left(None)
   *
   * scala> l1 combine l2
   * res0: EitherT[Option, String, Int] = EitherT(Some(Left(error 1)))
   *
   * scala> l1 combine r3
   * res1: EitherT[Option, String, Int] = EitherT(Some(Left(error 1)))
   *
   * scala> r3 combine l1
   * res2: EitherT[Option, String, Int] = EitherT(Some(Left(error 1)))
   *
   * scala> r3 combine r4
   * res3: EitherT[Option, String, Int] = EitherT(Some(Right(7)))
   *
   * scala> l1 combine noneEitherT
   * res4: EitherT[Option, String, Int] = EitherT(None)
   *
   * scala> noneEitherT combine l1
   * res5: EitherT[Option, String, Int] = EitherT(None)
   *
   * scala> r3 combine noneEitherT
   * res6: EitherT[Option, String, Int] = EitherT(None)
   *
   * scala> noneEitherT combine r4
   * res7: EitherT[Option, String, Int] = EitherT(None)
   * }}}
   */
  def combine(that: EitherT[F, A, B])(implicit F: Apply[F], B: Semigroup[B]): EitherT[F, A, B] =
    EitherT(F.map2(this.value, that.value)(_ combine _))

  def toValidated(implicit F: Functor[F]): F[Validated[A, B]] =
    F.map(value)(_.toValidated)

  def toValidatedNel(implicit F: Functor[F]): F[ValidatedNel[A, B]] =
    F.map(value)(_.toValidatedNel)

  /** Run this value as a `[[Validated]]` against the function and convert it back to an `[[EitherT]]`.
   *
   * The [[Applicative]] instance for `EitherT` "fails fast" - it is often useful to "momentarily" have
   * it accumulate errors instead, which is what the `[[Validated]]` data type gives us.
   *
   * Example:
   * {{{
   * scala> import cats.implicits._
   * scala> type Error = String
   * scala> val v1: Validated[NonEmptyList[Error], Int] = Validated.Invalid(NonEmptyList.of("error 1"))
   * scala> val v2: Validated[NonEmptyList[Error], Int] = Validated.Invalid(NonEmptyList.of("error 2"))
   * scala> val eithert: EitherT[Option, Error, Int] = EitherT(Some(Either.left("error 3")))
   * scala> eithert.withValidated { v3 => (v1 |@| v2 |@| v3.leftMap(NonEmptyList.of(_))).map{ case (i, j, k) => i + j + k } }
   * res0: EitherT[Option, NonEmptyList[Error], Int] = EitherT(Some(Left(NonEmptyList(error 1, error 2, error 3))))
   * }}}
   */
  def withValidated[AA, BB](f: Validated[A, B] => Validated[AA, BB])(implicit F: Functor[F]): EitherT[F, AA, BB] =
    EitherT(F.map(value)(either => f(either.toValidated).toEither))

  def show(implicit show: Show[F[Either[A, B]]]): String = show.show(value)

  /**
   * Transform this `EitherT[F, A, B]` into a `[[Nested]][F, Either[A, ?], B]`.
   *
   * An example where `toNested` can be used, is to get the `Apply.ap` function with the
   * behavior from the composed `Apply` instances from `F` and `Either[A, ?]`, which is
   * inconsistent with the behavior of the `ap` from `Monad` of `EitherT`.
   *
   * {{{
   * scala> import cats.data.{Nested, EitherT}
   * scala> import cats.implicits._
   * scala> val ff: EitherT[List, String, Int => String] =
   *      |   EitherT(List(Either.right(_.toString), Either.left("error")))
   * scala> val fa: EitherT[List, String, Int] =
   *      |   EitherT(List(Either.right(1), Either.right(2)))
   * scala> type ErrorOr[A] = Either[String, A]
   * scala> type ListErrorOr[A] = Nested[List, ErrorOr, A]
   * scala> ff.ap(fa)
   * res0: EitherT[List,String,String] = EitherT(List(Right(1), Right(2), Left(error)))
   * scala> EitherT((ff.toNested: ListErrorOr[Int => String]).ap(fa.toNested: ListErrorOr[Int]).value)
   * res1: EitherT[List,String,String] = EitherT(List(Right(1), Right(2), Left(error), Left(error)))
   * }}}
   *
   * Note that we need the `ErrorOr` type alias above because otherwise we can't use the
   * syntax function `ap` on `Nested[List, Either[A, ?], B]`. This won't be needed after cats has
   * decided [[https://github.com/typelevel/cats/issues/1073 how to handle the SI-2712 fix]].
   */
  def toNested: Nested[F, Either[A, ?], B] = Nested[F, Either[A, ?], B](value)

  /**
    * Transform this `EitherT[F, A, B]` into a `[[Nested]][F, Validated[A, ?], B]` or `[[Nested]][F, ValidatedNel[A, B]`.
    * Example:
    * {{{
    * scala> import cats.data.{Validated, EitherT, Nested}
    * scala> import cats.implicits._
    * scala> val f: Int => String = i => (i*2).toString
    * scala> val r1: EitherT[Option, String, Int => String] = EitherT.right(Some(f))
    *     | r1: cats.data.EitherT[Option,String,Int => String] = EitherT(Some(Right(<function1>)))
    * scala> val r2: EitherT[Option, String, Int] = EitherT.right(Some(10))
    *     | r2: cats.data.EitherT[Option,String,Int] = EitherT(Some(Right(10)))
    * scala> type ValidatedOr[A] = Validated[String, A]
    * scala> type OptionErrorOr[A] = Nested[Option, ValidatedOr, A]
    * scala> (r1.toNestedValidated: OptionErrorOr[Int => String]).ap(r2.toNestedValidated: OptionErrorOr[Int])
    * res0: OptionErrorOr[String] = Nested(Some(Valid(20)))
    * }}}
    */
  def toNestedValidated(implicit F: Functor[F]): Nested[F, Validated[A, ?], B] =
    Nested[F, Validated[A, ?], B](F.map(value)(_.toValidated))

  def toNestedValidatedNel(implicit F: Functor[F]): Nested[F, ValidatedNel[A, ?], B] =
    Nested[F, ValidatedNel[A, ?], B](F.map(value)(_.toValidatedNel))
}

object EitherT extends EitherTInstances with EitherTFunctions

trait EitherTFunctions {
  final def left[F[_], A, B](fa: F[A])(implicit F: Functor[F]): EitherT[F, A, B] = EitherT(F.map(fa)(Either.left))

  final def right[F[_], A, B](fb: F[B])(implicit F: Functor[F]): EitherT[F, A, B] = EitherT(F.map(fb)(Either.right))

  final def pure[F[_], A, B](b: B)(implicit F: Applicative[F]): EitherT[F, A, B] = right(F.pure(b))

  /**
   * Alias for [[right]]
   * {{{
   * scala> import cats.data.EitherT
   * scala> import cats.implicits._
   * scala> val o: Option[Int] = Some(3)
   * scala> val n: Option[Int] = None
   * scala> EitherT.liftT(o)
   * res0: cats.data.EitherT[Option,Nothing,Int] = EitherT(Some(Right(3)))
   * scala> EitherT.liftT(n)
   * res1: cats.data.EitherT[Option,Nothing,Int] = EitherT(None)
   * }}}
   */
  final def liftT[F[_], A, B](fb: F[B])(implicit F: Functor[F]): EitherT[F, A, B] = right(fb)

  /** Transforms an `Either` into an `EitherT`, lifted into the specified `Applicative`.
   *
   * Note: The return type is a FromEitherPartiallyApplied[F], which has an apply method
   * on it, allowing you to call fromEither like this:
   * {{{
   * scala> import cats.implicits._
   * scala> val t: Either[String, Int] = Either.right(3)
   * scala> EitherT.fromEither[Option](t)
   * res0: EitherT[Option, String, Int] = EitherT(Some(Right(3)))
   * }}}
   *
   * The reason for the indirection is to emulate currying type parameters.
   */
  final def fromEither[F[_]]: FromEitherPartiallyApplied[F] = new FromEitherPartiallyApplied

  final class FromEitherPartiallyApplied[F[_]] private[EitherTFunctions] {
    def apply[E, A](either: Either[E, A])(implicit F: Applicative[F]): EitherT[F, E, A] =
      EitherT(F.pure(either))
  }

  /** Transforms an `Option` into an `EitherT`, lifted into the specified `Applicative` and using
   *  the second argument if the `Option` is a `None`.
   * {{{
   * scala> import cats.implicits._
   * scala> val o: Option[Int] = None
   * scala> EitherT.fromOption[List](o, "Answer not known.")
   * res0: EitherT[List, String, Int]  = EitherT(List(Left(Answer not known.)))
   * scala> EitherT.fromOption[List](Some(42), "Answer not known.")
   * res1: EitherT[List, String, Int] = EitherT(List(Right(42)))
   * }}}
   */
  final def fromOption[F[_]]: FromOptionPartiallyApplied[F] = new FromOptionPartiallyApplied

  final class FromOptionPartiallyApplied[F[_]] private[EitherTFunctions] {
    def apply[E, A](opt: Option[A], ifNone: => E)(implicit F: Applicative[F]): EitherT[F, E, A] =
      EitherT(F.pure(Either.fromOption(opt, ifNone)))
  }
}

private[data] abstract class EitherTInstances extends EitherTInstances1 {

  /* TODO violates right absorbtion, right distributivity, and left distributivity -- re-enable when MonadCombine laws are split in to weak/strong
  implicit def catsDataMonadCombineForEitherT[F[_], L](implicit F: Monad[F], L: Monoid[L]): MonadCombine[EitherT[F, L, ?]] = {
    implicit val F0 = F
    implicit val L0 = L
    new EitherTMonadCombine[F, L] { implicit val F = F0; implicit val L = L0 }
  }
  */

  implicit def catsDataOrderForEitherT[F[_], L, R](implicit F: Order[F[Either[L, R]]]): Order[EitherT[F, L, R]] =
    new EitherTOrder[F, L, R] {
      val F0: Order[F[Either[L, R]]] = F
    }

  implicit def catsDataShowForEitherT[F[_], L, R](implicit sh: Show[F[Either[L, R]]]): Show[EitherT[F, L, R]] =
    functor.Contravariant[Show].contramap(sh)(_.value)

  implicit def catsDataBifunctorForEitherT[F[_]](implicit F: Functor[F]): Bifunctor[EitherT[F, ?, ?]] =
    new Bifunctor[EitherT[F, ?, ?]] {
      override def bimap[A, B, C, D](fab: EitherT[F, A, B])(f: A => C, g: B => D): EitherT[F, C, D] = fab.bimap(f, g)
    }

  implicit def catsDataTraverseForEitherT[F[_], L](implicit F: Traverse[F]): Traverse[EitherT[F, L, ?]] =
    new EitherTTraverse[F, L] {
      val F0: Traverse[F] = F
    }

  implicit def catsDataTransLiftForEitherT[E]: TransLift.Aux[EitherT[?[_], E, ?], Functor] =
    new TransLift[EitherT[?[_], E, ?]] {
      type TC[M[_]] = Functor[M]

      def liftT[M[_]: Functor, A](ma: M[A]): EitherT[M, E, A] =
        EitherT.liftT(ma)
    }

  implicit def catsMonoidForEitherT[F[_], L, A](implicit F: Monoid[F[Either[L, A]]]): Monoid[EitherT[F, L, A]] =
    new EitherTMonoid[F, L, A] { implicit val F0 = F }

}

private[data] abstract class EitherTInstances1 extends EitherTInstances2 {
  /* TODO violates monadFilter right empty law -- re-enable when MonadFilter laws are split in to weak/strong
  implicit def catsDataMonadFilterForEitherT[F[_], L](implicit F: Monad[F], L: Monoid[L]): MonadFilter[EitherT[F, L, ?]] = {
    implicit val F0 = F
    implicit val L0 = L
    new EitherTMonadFilter[F, L] { implicit val F = F0; implicit val L = L0 }
  }
   */

  implicit def catsSemigroupForEitherT[F[_], L, A](implicit F: Semigroup[F[Either[L, A]]]): Semigroup[EitherT[F, L, A]] =
    new EitherTSemigroup[F, L, A] { implicit val F0 = F }

  implicit def catsDataFoldableForEitherT[F[_], L](implicit F: Foldable[F]): Foldable[EitherT[F, L, ?]] =
    new EitherTFoldable[F, L] {
      val F0: Foldable[F] = F
    }

  implicit def catsDataPartialOrderForEitherT[F[_], L, R](implicit F: PartialOrder[F[Either[L, R]]]): PartialOrder[EitherT[F, L, R]] =
    new EitherTPartialOrder[F, L, R] {
      val F0: PartialOrder[F[Either[L, R]]] = F
    }

  implicit def catsDataBitraverseForEitherT[F[_]](implicit F: Traverse[F]): Bitraverse[EitherT[F, ?, ?]] =
    new EitherTBitraverse[F] {
      val F0: Traverse[F] = F
    }
}

private[data] abstract class EitherTInstances2 extends EitherTInstances3 {
  implicit def catsDataMonadErrorForEitherT[F[_], L](implicit F0: Monad[F]): MonadError[EitherT[F, L, ?], L] =
    new EitherTMonadError[F, L] { implicit val F = F0 }

  implicit def catsDataSemigroupKForEitherT[F[_], L](implicit F0: Monad[F]): SemigroupK[EitherT[F, L, ?]] =
    new EitherTSemigroupK[F, L] { implicit val F = F0 }

  implicit def catsDataEqForEitherT[F[_], L, R](implicit F: Eq[F[Either[L, R]]]): Eq[EitherT[F, L, R]] =
    new EitherTEq[F, L, R] {
      val F0: Eq[F[Either[L, R]]] = F
    }
}

private[data] abstract class EitherTInstances3 {
  implicit def catsDataFunctorForEitherT[F[_], L](implicit F0: Functor[F]): Functor[EitherT[F, L, ?]] =
    new EitherTFunctor[F, L] { implicit val F = F0 }
}

private[data] trait EitherTSemigroup[F[_], L, A] extends Semigroup[EitherT[F, L, A]] {
  implicit val F0: Semigroup[F[Either[L, A]]]
  def combine(x: EitherT[F, L , A], y: EitherT[F, L , A]): EitherT[F, L , A] =
    EitherT(F0.combine(x.value, y.value))
}

private[data] trait EitherTMonoid[F[_], L, A] extends Monoid[EitherT[F, L, A]] with EitherTSemigroup[F, L, A] {
  implicit val F0: Monoid[F[Either[L, A]]]
  def empty: EitherT[F, L, A] = EitherT(F0.empty)
}

private[data] trait EitherTSemigroupK[F[_], L] extends SemigroupK[EitherT[F, L, ?]] {
  implicit val F: Monad[F]
  def combineK[A](x: EitherT[F, L, A], y: EitherT[F, L, A]): EitherT[F, L, A] =
    EitherT(F.flatMap(x.value) {
      case l @ Left(_) => y.value
      case r @ Right(_) => F.pure(r)
    })
}

private[data] trait EitherTFunctor[F[_], L] extends Functor[EitherT[F, L, ?]] {
  implicit val F: Functor[F]
  override def map[A, B](fa: EitherT[F, L, A])(f: A => B): EitherT[F, L, B] = fa map f
}

private[data] trait EitherTMonad[F[_], L] extends Monad[EitherT[F, L, ?]] with EitherTFunctor[F, L] {
  implicit val F: Monad[F]
  def pure[A](a: A): EitherT[F, L, A] = EitherT(F.pure(Either.right(a)))
  def flatMap[A, B](fa: EitherT[F, L, A])(f: A => EitherT[F, L, B]): EitherT[F, L, B] = fa flatMap f
  def tailRecM[A, B](a: A)(f: A => EitherT[F, L, Either[A, B]]): EitherT[F, L, B] =
    EitherT(F.tailRecM(a)(a0 => F.map(f(a0).value) {
      case Left(l)         => Right(Left(l))
      case Right(Left(a1)) => Left(a1)
      case Right(Right(b)) => Right(Right(b))
    }))
}

private[data] trait EitherTMonadError[F[_], L] extends MonadError[EitherT[F, L, ?], L] with EitherTMonad[F, L] {
  def handleErrorWith[A](fea: EitherT[F, L, A])(f: L => EitherT[F, L, A]): EitherT[F, L, A] =
    EitherT(F.flatMap(fea.value) {
      case Left(e) => f(e).value
      case r @ Right(_) => F.pure(r)
    })
  override def handleError[A](fea: EitherT[F, L, A])(f: L => A): EitherT[F, L, A] =
    EitherT(F.flatMap(fea.value) {
      case Left(e) => F.pure(Right(f(e)))
      case r @ Right(_) => F.pure(r)
    })
  def raiseError[A](e: L): EitherT[F, L, A] = EitherT.left(F.pure(e))
  override def attempt[A](fla: EitherT[F, L, A]): EitherT[F, L, Either[L, A]] = EitherT.right(fla.value)
  override def recover[A](fla: EitherT[F, L, A])(pf: PartialFunction[L, A]): EitherT[F, L, A] =
    fla.recover(pf)
  override def recoverWith[A](fla: EitherT[F, L, A])(pf: PartialFunction[L, EitherT[F, L, A]]): EitherT[F, L, A] =
    fla.recoverWith(pf)
}

private[data] trait EitherTMonadFilter[F[_], L] extends MonadFilter[EitherT[F, L, ?]] with EitherTMonadError[F, L] {
  implicit val F: Monad[F]
  implicit val L: Monoid[L]
  def empty[A]: EitherT[F, L, A] = EitherT(F.pure(Either.left(L.empty)))
}

/* TODO violates right absorbtion, right distributivity, and left distributivity -- re-enable when MonadCombine laws are split in to weak/strong
private[data] trait EitherTMonadCombine[F[_], L] extends MonadCombine[EitherT[F, L, ?]] with EitherTMonadFilter[F, L] with EitherTSemigroupK[F, L] {
  implicit val F: Monad[F]
  implicit val L: Monoid[L]
}
*/

private[data] sealed trait EitherTFoldable[F[_], L] extends Foldable[EitherT[F, L, ?]] {
  implicit def F0: Foldable[F]

  def foldLeft[A, B](fa: EitherT[F, L, A], b: B)(f: (B, A) => B): B =
    fa.foldLeft(b)(f)

  def foldRight[A, B](fa: EitherT[F, L, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
    fa.foldRight(lb)(f)
}

private[data] sealed trait EitherTTraverse[F[_], L] extends Traverse[EitherT[F, L, ?]] with EitherTFoldable[F, L] {
  override implicit def F0: Traverse[F]

  override def traverse[G[_]: Applicative, A, B](fa: EitherT[F, L, A])(f: A => G[B]): G[EitherT[F, L, B]] =
    fa traverse f
}

private[data] sealed trait EitherTBifoldable[F[_]] extends Bifoldable[EitherT[F, ?, ?]] {
  implicit def F0: Foldable[F]

  def bifoldLeft[A, B, C](fab: EitherT[F, A, B], c: C)(f: (C, A) => C, g: (C, B) => C): C =
    F0.foldLeft(fab.value, c)( (acc, axb) => Bifoldable[Either].bifoldLeft(axb, acc)(f, g))

  def bifoldRight[A, B, C](fab: EitherT[F, A, B], c: Eval[C])(f: (A, Eval[C]) => Eval[C], g: (B, Eval[C]) => Eval[C]): Eval[C] =
    F0.foldRight(fab.value, c)( (axb, acc) => Bifoldable[Either].bifoldRight(axb, acc)(f, g))
}

private[data] sealed trait EitherTBitraverse[F[_]] extends Bitraverse[EitherT[F, ?, ?]] with EitherTBifoldable[F] {
  override implicit def F0: Traverse[F]

  override def bitraverse[G[_], A, B, C, D](fab: EitherT[F, A, B])(f: A => G[C], g: B => G[D])(implicit G: Applicative[G]): G[EitherT[F, C, D]] =
    fab.bitraverse(f, g)
}

private[data] sealed trait EitherTEq[F[_], L, A] extends Eq[EitherT[F, L, A]] {
  implicit def F0: Eq[F[Either[L, A]]]

  override def eqv(x: EitherT[F, L, A], y: EitherT[F, L, A]): Boolean = x === y
}

private[data] sealed trait EitherTPartialOrder[F[_], L, A] extends PartialOrder[EitherT[F, L, A]] with EitherTEq[F, L, A]{
  override implicit def F0: PartialOrder[F[Either[L, A]]]

  override def partialCompare(x: EitherT[F, L, A], y: EitherT[F, L, A]): Double =
    x partialCompare y
}

private[data] sealed trait EitherTOrder[F[_], L, A] extends Order[EitherT[F, L, A]] with EitherTPartialOrder[F, L, A]{
  override implicit def F0: Order[F[Either[L, A]]]

  override def compare(x: EitherT[F, L, A], y: EitherT[F, L, A]): Int = x compare y
}
