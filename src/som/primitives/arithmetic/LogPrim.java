package som.primitives.arithmetic;

import som.interpreter.nodes.nary.UnaryExpressionNode;
import som.primitives.Primitive;

import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;


@GenerateNodeFactory
@Primitive("doubleLog:")
public abstract class LogPrim extends UnaryExpressionNode {

  @Specialization
  public final double doLog(final double rcvr) {
    return Math.log(rcvr);
  }
}