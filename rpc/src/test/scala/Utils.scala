/*
 * Copyright 2017 47 Degrees, LLC. <http://www.47deg.com>
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

package freestyle.rpc

import cats.{~>, Monad, MonadError}
import freestyle._
import freestyle.rpc.client._
import freestyle.rpc.protocol._
import freestyle.rpc.server._
import io.grpc.ManagedChannel
import monix.eval.Task
import monix.reactive.Observable

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object Utils {

  object service {

    @message
    case class A(x: Int, y: Int)

    @message
    case class B(a1: A, a2: A)

    @message
    case class C(foo: String, a: A)

    @message
    case class D(bar: Int)

    @message
    case class E(a: A, foo: String)

    @service
    trait RPCService[F[_]] {

      @rpc(Protobuf) def notAllowed(b: Boolean): F[C]

      @rpc(Protobuf) def empty(empty: Empty): F[Empty]

      @rpc(Avro) def unary(a: A): F[C]

      @rpc(Protobuf)
      @stream[ResponseStreaming.type]
      def serverStreaming(b: B): F[Observable[C]]

      @rpc(Protobuf)
      @stream[RequestStreaming.type]
      def clientStreaming(oa: Observable[A]): F[D]

      @rpc(Avro)
      @stream[BidirectionalStreaming.type]
      def biStreaming(oe: Observable[E]): F[Observable[E]]
    }

  }

  object database {

    import service._

    val i: Int = 5
    val a1: A  = A(1, 2)
    val a2: A  = A(10, 20)
    val a3: A  = A(100, 200)
    val a4: A  = A(1000, 2000)
    val c1: C  = C("foo1", a1)
    val c2: C  = C("foo2", a1)
    val e1: E  = E(a3, "foo3")
    val e2: E  = E(a4, "foo4")

    val cList = List(c1, c2)
    val eList = List(e1, e2)

    val dResult: D = D(6)
  }

  object handlers {

    object server {

      import database._
      import service._

      class ServerRPCService[F[_]](implicit C: Capture[F], T2F: Task ~> F) extends RPCService[F] {
        import database._

        def notAllowed(b: Boolean): F[C] = C.capture(c1)

        def empty(empty: Empty): F[Empty] = C.capture(Empty())

        def unary(a: A): F[C] =
          C.capture(c1)

        def serverStreaming(b: B): F[Observable[C]] = {
          helpers.debug(s"[SERVER] b -> $b")
          val obs = Observable.fromIterable(cList)
          C.capture(obs)
        }

        def clientStreaming(oa: Observable[A]): F[D] =
          T2F(
            oa.foldLeftL(D(0)) {
              case (current, a) =>
                helpers.debug(s"[SERVER] Current -> $current / a -> $a")
                D(current.bar + a.x + a.y)
            }
          )

        def biStreaming(oe: Observable[E]): F[Observable[E]] =
          C.capture {
            oe.flatMap { e: E =>
              save(e)

              Observable.fromIterable(eList)
            }
          }

        def save(e: E) = e // do something else with e?
      }
    }

    object client {

      import freestyle.rpc.FreeUtils.clientProgram.MyRPCClient
      import service._
      import cats.implicits._

      class ClientRPCService[F[_]: Monad](
          implicit client: RPCService.Client[F],
          M: MonadError[F, Throwable],
          T2F: Task ~> F) {

        def notAllowed(b: Boolean): F[C] =
          client.notAllowed(b)

        def empty: F[Empty] =
          client.empty(protocol.Empty())

        def u(x: Int, y: Int): F[C] =
          client.unary(A(x, y))

        def ss(a: Int, b: Int): F[List[C]] = T2F {
          client
            .serverStreaming(B(A(a, a), A(b, b)))
            .zipWithIndex
            .map {
              case (c, i) =>
                helpers.debug(s"[CLIENT] Result #$i: $c")
                c
            }
            .toListL
        }

        def cs(cList: List[C], bar: Int): F[D] =
          client.clientStreaming(Observable.fromIterable(cList.map(c => c.a)))

        def bs(eList: List[E]): F[E] =
          T2F(
            client
              .biStreaming(Observable.fromIterable(eList))
              .firstL)
      }
    }
  }

  object clientProgram {

    import service._

    @free
    trait MyRPCClient {
      def notAllowed(b: Boolean): FS[C]
      def empty: FS[Empty]
      def u(x: Int, y: Int): FS[C]
      def ss(a: Int, b: Int): FS[List[C]]
      def cs(cList: List[C], bar: Int): FS[D]
      def bs(eList: List[E]): FS[E]
    }
  }

  object helpers {

    import cats.implicits._
    import freestyle.implicits._
    import freestyle.config.implicits._

    def createManagedChannel: ManagedChannel = {

      val channelFor: ManagedChannelFor =
        ConfigForAddress[ChannelConfig.Op]("rpc.client.host", "rpc.client.port")
          .interpret[Try] match {
          case Success(c) => c
          case Failure(e) =>
            e.printStackTrace()
            throw new RuntimeException("Unable to load the client configuration", e)
        }

      val channelConfigList: List[ManagedChannelConfig] = List(UsePlaintext(true))

      val managedChannelInterpreter =
        new ManagedChannelInterpreter[Future](channelFor, channelConfigList)

      managedChannelInterpreter.build(channelFor, channelConfigList)
    }

    def createServerConf(grpcConfigs: List[GrpcConfig]): ServerW =
      BuildServerFromConfig[ServerConfig.Op]("rpc.server.port", grpcConfigs)
        .interpret[Try] match {
        case Success(c) => c
        case Failure(e) =>
          e.printStackTrace()
          throw new RuntimeException("Unable to load the server configuration", e)
      }

    def serverStart[M[_]](implicit APP: GrpcServerApp[M]): FreeS[M, Unit] = {
      val server = APP.server
      val log    = APP.log
      for {
        _    <- server.start()
        port <- server.getPort
        _    <- log.info(s"Server started, listening on $port")
      } yield ()
    }

    def serverStop[M[_]](implicit APP: GrpcServerApp[M]): FreeS[M, Unit] = {
      val server = APP.server
      val log    = APP.log
      for {
        port <- server.getPort
        _    <- log.info(s"Stopping server listening on $port")
        _    <- server.shutdownNow()
      } yield ()
    }

    def debug(str: String): Unit =
      println(s"\n\n$str\n\n")

  }

  trait FreesRuntime {

    import service._
    import helpers._
    import handlers.server._
    import handlers.client._
    import cats.implicits._
    import freestyle.rpc.server._
    import freestyle.rpc.server.implicits._
    import freestyle.rpc.server.handlers._

    implicit val ec: ExecutionContext         = ExecutionContext.Implicits.global
    implicit val S: monix.execution.Scheduler = monix.execution.Scheduler.Implicits.global

    //////////////////////////////////
    // Server Runtime Configuration //
    //////////////////////////////////

    implicit val freesRPCHandler: ServerRPCService[Future] =
      new ServerRPCService[Future]

    def grpcConfigs: List[GrpcConfig] = List(
      AddService(RPCService.bindService[Future])
    )

    implicit val grpcServerHandler: GrpcServer.Op ~> Future =
      new GrpcServerHandler[Future] andThen
        new GrpcKInterpreter[Future](createServerConf(grpcConfigs).server)

    //////////////////////////////////
    // Client Runtime Configuration //
    //////////////////////////////////

    implicit val freesRPCServiceClient: RPCService.Client[Future] =
      RPCService.client[Future](createManagedChannel)

    implicit val freesRPCServiceClientHandler: ClientRPCService[Future] =
      new ClientRPCService[Future]

    ////////////
    // Syntax //
    ////////////

    implicit class InterpreterOps[F[_], A](fs: FreeS[F, A])(implicit H: FSHandler[F, Future]) {

      def runF: A = Await.result(fs.interpret[Future], Duration.Inf)

    }

  }

  object implicits extends FreesRuntime

}
