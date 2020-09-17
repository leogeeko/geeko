package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

sealed abstract class VM[Ctx <: Context](ctx: Ctx,
                                         frameStack: Stack[Frame[Ctx]],
                                         operandStack: Stack[Val]) {
  def execute(obj: ContractObj[Ctx], methodIndex: Int, args: AVector[Val]): ExeResult[Unit] = {
    for {
      startFrame <- obj.startFrame(ctx, methodIndex, args, operandStack)
      _          <- frameStack.push(startFrame)
      _          <- executeFrames()
    } yield ()
  }

  def executeWithOutputs(obj: ContractObj[Ctx],
                         methodIndex: Int,
                         args: AVector[Val]): ExeResult[AVector[Val]] = {
    var outputs: AVector[Val] = AVector.ofSize(0)
    val returnTo: AVector[Val] => ExeResult[Unit] = returns => { outputs = returns; Right(()) }
    for {
      startFrame <- obj.startFrameWithOutputs(ctx, methodIndex, args, operandStack, returnTo)
      _          <- frameStack.push(startFrame)
      _          <- executeFrames()
    } yield outputs
  }

  @tailrec
  private def executeFrames(): ExeResult[Unit] = {
    if (frameStack.nonEmpty) {
      val currentFrame = frameStack.topUnsafe
      if (currentFrame.isComplete) {
        frameStack.pop()
        executeFrames()
      } else {
        currentFrame.execute()
      }
    } else {
      Right(())
    }
  }
}

final class StatelessVM(ctx: StatelessContext,
                        frameStack: Stack[Frame[StatelessContext]],
                        operandStack: Stack[Val])
    extends VM(ctx, frameStack, operandStack)

final class StatefulVM(ctx: StatefulContext,
                       frameStack: Stack[Frame[StatefulContext]],
                       operandStack: Stack[Val])
    extends VM(ctx, frameStack, operandStack)

object StatelessVM {
  def runAssetScript(worldState: WorldState,
                     txHash: Hash,
                     script: StatelessScript,
                     args: AVector[Val],
                     signature: Signature): ExeResult[Unit] = {
    val context = StatelessContext(txHash, signature, worldState)
    val obj     = script.toObject
    execute(context, obj, args)
  }

  def runAssetScript(worldState: WorldState,
                     txHash: Hash,
                     script: StatelessScript,
                     args: AVector[Val],
                     signatures: Stack[Signature]): ExeResult[Unit] = {
    val context = StatelessContext(txHash, signatures, worldState)
    val obj     = script.toObject
    execute(context, obj, args)
  }

  def execute(context: StatelessContext,
              obj: ContractObj[StatelessContext],
              args: AVector[Val]): ExeResult[Unit] = {
    val vm = new StatelessVM(context,
                             Stack.ofCapacity(frameStackMaxSize),
                             Stack.ofCapacity(opStackMaxSize))
    vm.execute(obj, 0, args)
  }

  def executeWithOutputs(context: StatelessContext,
                         obj: ContractObj[StatelessContext],
                         args: AVector[Val]): ExeResult[AVector[Val]] = {
    val vm = new StatelessVM(context,
                             Stack.ofCapacity(frameStackMaxSize),
                             Stack.ofCapacity(opStackMaxSize))
    vm.executeWithOutputs(obj, 0, args)
  }
}

object StatefulVM {
  def runTxScripts(worldState: WorldState, block: Block): ExeResult[WorldState] = {
    block.transactions.foldE(worldState) {
      case (worldState, tx) =>
        tx.unsigned.scriptOpt match {
          case Some(script) => runTxScript(worldState, tx.hash, script)
          case None         => Right(worldState)
        }
    }
  }

  def runTxScript(worldState: WorldState,
                  txHash: Hash,
                  script: StatefulScript): ExeResult[WorldState] = {
    val context = StatefulContext(txHash, worldState)
    val obj     = script.toObject
    execute(context, obj, AVector.empty).map(_ => context.worldState)
  }

  def execute(context: StatefulContext,
              obj: ContractObj[StatefulContext],
              args: AVector[Val]): ExeResult[WorldState] = {
    val vm =
      new StatefulVM(context, Stack.ofCapacity(frameStackMaxSize), Stack.ofCapacity(opStackMaxSize))
    vm.execute(obj, 0, args).map(_ => context.worldState)
  }

  def executeWithOutputs(context: StatefulContext,
                         obj: ContractObj[StatefulContext],
                         args: AVector[Val]): ExeResult[AVector[Val]] = {
    val vm =
      new StatefulVM(context, Stack.ofCapacity(frameStackMaxSize), Stack.ofCapacity(opStackMaxSize))
    vm.executeWithOutputs(obj, 0, args)
  }
}
