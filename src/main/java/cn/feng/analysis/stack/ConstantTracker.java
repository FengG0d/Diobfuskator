package cn.feng.analysis.stack;

import cn.feng.analysis.SuperInterpreter;
import cn.feng.util.reflection.Casts;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;


/**
 * @author Holger https://stackoverflow
 * .com/users/2711488/holger (Modified version)
 */
public class ConstantTracker extends Interpreter<ConstantValue> implements Opcodes {
    public static final ConstantValue NULL = new ConstantValue(BasicValue.REFERENCE_VALUE, null);

    SuperInterpreter basic = new SuperInterpreter();

    private Object[] args;
    private Type[] desc;

    private final IConstantReferenceHandler referenceHandler;

    public ConstantTracker(IConstantReferenceHandler referenceHandler) {
        super(ASM8);
        this.referenceHandler = referenceHandler;
    }

    public ConstantTracker(IConstantReferenceHandler referenceHandler, boolean isStatic, int localVariables, String descr,
                           Object[] args) {
        super(ASM8);
        this.referenceHandler = referenceHandler;
        this.desc = Type.getArgumentTypes(descr);
        ArrayList<Object> reformatted = new ArrayList<>();
        if (!isStatic) {
            reformatted.add(null); // this reference
        }
        for (int i = 0; i < desc.length; ++i) {
            reformatted.add(i >= args.length ? null : args[i]);
            if (desc[i].getSize() == 2) {
                reformatted.add(null); // placeholder for long and
                // double
            }
        }
        if (reformatted.size() > localVariables) {
            // this shouldn't happen...
            // throw new IllegalArgumentException();
        }
        while (reformatted.size() < localVariables) {
            // add placeholder for remaining local variables
            reformatted.add(null);
        }
        this.args = reformatted.toArray(new Object[0]);
    }

    @Override
    public ConstantValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
        BasicValue v = basic.newOperation(insn);
        switch (insn.getOpcode()) {
            case ACONST_NULL:
                return NULL;
            case ICONST_M1:
            case ICONST_0:
            case ICONST_1:
            case ICONST_2:
            case ICONST_3:
            case ICONST_4:
            case ICONST_5:
                return new ConstantValue(BasicValue.INT_VALUE, insn.getOpcode() - ICONST_0);
            case LCONST_0:
            case LCONST_1:
                return new ConstantValue(BasicValue.LONG_VALUE, (long) (insn.getOpcode() - LCONST_0));
            case FCONST_0:
            case FCONST_1:
            case FCONST_2:
                return new ConstantValue(BasicValue.FLOAT_VALUE, (float) (insn.getOpcode() - FCONST_0));
            case DCONST_0:
            case DCONST_1:
                return new ConstantValue(BasicValue.DOUBLE_VALUE, (double) (insn.getOpcode() - DCONST_0));
            case BIPUSH:
            case SIPUSH:
                return new ConstantValue(BasicValue.INT_VALUE, ((IntInsnNode) insn).operand);
            case LDC:
                return new ConstantValue(v, ((LdcInsnNode) insn).cst);
            case GETSTATIC:
                FieldInsnNode fin = (FieldInsnNode) insn;

                Object o = referenceHandler.getFieldValueOrNull(v, fin.owner, fin.name, fin.desc);
                return new ConstantValue(v, o);
            default:
                return v == null ? null : new ConstantValue(v, null);
        }
    }

    @Override
    public ConstantValue copyOperation(AbstractInsnNode insn, ConstantValue value) {
        if (args != null && !value.isKnown()) {
            // unloaded arguments
            switch (insn.getOpcode()) {
                case ALOAD:
                case ILOAD:
                case LLOAD:
                case DLOAD:
                case FLOAD:
                    int var = ((VarInsnNode) insn).var;
                    if (var < args.length) {
                        Object val = args[var];
                        value.setValue(val);
                    }
                    break;
                default:
                    break;
            }
        }
        return value;
    }

    @Override
    public ConstantValue newValue(Type type) {
        BasicValue v = basic.newValue(type);
        return v == null ? null : new ConstantValue(v, null);
    }

    @Override
    public ConstantValue unaryOperation(AbstractInsnNode insn, ConstantValue value) throws AnalyzerException {
        BasicValue v = basic.unaryOperation(insn, value.getType());
        switch (insn.getOpcode()) {
            case GETFIELD:
                FieldInsnNode fin = (FieldInsnNode) insn;
                return new ConstantValue(v, referenceHandler.getFieldValueOrNull(v, fin.owner, fin.name, fin.desc));
            case NEWARRAY:
                Integer size = value.getAsInteger();
                if (size != null) {
                    switch (((IntInsnNode) insn).operand) {
                        case T_BOOLEAN:
                            return new ConstantValue(v, new boolean[size]);
                        case T_CHAR:
                            return new ConstantValue(v, new char[size]);
                        case T_BYTE:
                            return new ConstantValue(v, new byte[size]);
                        case T_SHORT:
                            return new ConstantValue(v, new short[size]);
                        case T_INT:
                            return new ConstantValue(v, new int[size]);
                        case T_FLOAT:
                            return new ConstantValue(v, new float[size]);
                        case T_DOUBLE:
                            return new ConstantValue(v, new double[size]);
                        case T_LONG:
                            return new ConstantValue(v, new long[size]);
                    }
                }
            case ARRAYLENGTH:
                if (value.value != null) {
                    Class<?> clz = value.value.getClass();
                    if (clz.isArray() && clz.getComponentType().isPrimitive()) {
                        return new ConstantValue(v, Array.getLength(value.value));
                    }
                }
            default:
                return v == null ? null : new ConstantValue(v, getUnaryValue(insn.getOpcode(), value.value));
        }
    }

    private Object getUnaryValue(int opcode, Object value) {
        if (!(value instanceof Number numVal))
            return null;
        switch (opcode) {
            case INEG:
                return -((int) value);
            case FNEG:
                return -((float) value);
            case LNEG:
                return -((long) value);
            case DNEG:
                return -((double) value);
            case L2I:
            case F2I:
            case D2I:
                return numVal.intValue();
            case I2B:
                return numVal.byteValue();
            case I2C:
                return numVal.intValue() & 0x0000FFFF;
            case I2S:
                return numVal.shortValue();
            case I2F:
            case L2F:
            case D2F:
                return numVal.floatValue();
            case I2L:
            case F2L:
            case D2L:
                return numVal.longValue();
            case I2D:
            case L2D:
            case F2D:
                return numVal.doubleValue();
            case CHECKCAST:
                return value;
            default:
                return null;
        }
    }

    @Override
    public ConstantValue binaryOperation(AbstractInsnNode insn, ConstantValue a, ConstantValue b)
            throws AnalyzerException {
        BasicValue v = basic.binaryOperation(insn, a.getType(), b.getType());
        return switch (insn.getOpcode()) {
            case BALOAD, CALOAD, SALOAD, IALOAD, FALOAD, DALOAD, LALOAD, AALOAD -> {
                if (a.isKnown() && b.isKnown() && b.isInteger()) {
                    int index = b.getAsInteger();
                    // we do not want an OOB exception here, just
                    // keep it unknown
                    if (index >= 0 && index < Array.getLength(a.value)) {
                        if (insn.getOpcode() == AALOAD)
                            yield new ConstantValue(v, Array.get(a.value, index));
                        else
                            yield new ConstantValue(v, Casts.toNumber(Array.get(a.value, index)));
                    }
                }
                yield new ConstantValue(v, null);
                // we do not want an OOB exception here, just
                // keep it unknown
            }
            default -> v == null ? null : new ConstantValue(v, getBinaryValue(insn.getOpcode(), a.value, b.value));
        };
    }

    private Object getBinaryValue(int opcode, Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        if (!(a instanceof Number num1) || !(b instanceof Number num2)) {
            return null;
        }
        // array load instructions not handled
        return switch (opcode) {
            case IADD -> num1.intValue() + num2.intValue();
            case ISUB -> num1.intValue() - num2.intValue();
            case IMUL -> num1.intValue() * num2.intValue();
            case IDIV -> {
                if (num2.intValue() == 0) {
                    // we do not want arithmetic exceptions
                    yield null;
                }
                yield num1.intValue() / num2.intValue();
                // we do not want arithmetic exceptions
            }
            case IREM -> {
                if (num2.intValue() == 0) {
                    // we do not want arithmetic exceptions
                    yield null;
                }
                yield num1.intValue() % num2.intValue();
                // we do not want arithmetic exceptions
            }
            case ISHL -> num1.intValue() << num2.intValue();
            case ISHR -> num1.intValue() >> num2.intValue();
            case IUSHR -> num1.intValue() >>> num2.intValue();
            case IAND -> num1.intValue() & num2.intValue();
            case IOR -> num1.intValue() | num2.intValue();
            case IXOR -> num1.intValue() ^ num2.intValue();
            case FADD -> num1.floatValue() + num2.floatValue();
            case FSUB -> num1.floatValue() - num2.floatValue();
            case FMUL -> num1.floatValue() * num2.floatValue();
            case FDIV -> num1.floatValue() / num2.floatValue();
            case FREM -> num1.floatValue() % num2.floatValue();
            case LADD -> num1.longValue() + num2.longValue();
            case LSUB -> num1.longValue() - num2.longValue();
            case LMUL -> num1.longValue() * num2.longValue();
            case LDIV -> {
                if (num2.intValue() == 0) {
                    // we do not want arithmetic exceptions
                    yield null;
                }
                yield num1.longValue() / num2.longValue();
                // we do not want arithmetic exceptions
            }
            case LREM -> {
                if (num2.intValue() == 0) {
                    // we do not want arithmetic exceptions
                    yield null;
                }
                yield num1.longValue() % num2.longValue();
                // we do not want arithmetic exceptions
            }
            case LSHL -> num1.longValue() << num2.intValue();
            case LSHR -> num1.longValue() >> num2.intValue();
            case LUSHR -> num1.longValue() >>> num2.intValue();
            case LAND -> num1.longValue() & num2.longValue();
            case LOR -> num1.longValue() | num2.longValue();
            case LXOR -> num1.longValue() ^ num2.longValue();
            case DADD -> num1.doubleValue() + num2.doubleValue();
            case DSUB -> num1.doubleValue() - num2.doubleValue();
            case DMUL -> num1.doubleValue() * num2.doubleValue();
            case DDIV -> num1.doubleValue() / num2.doubleValue();
            case DREM -> num1.doubleValue() % num2.doubleValue();

            // compare instructions not tested, could return
            // wrong result
            case LCMP -> Long.compare(num1.longValue(), num2.longValue());
            case FCMPL, FCMPG ->
                // no NaN handling, could affect results
                    Float.compare(num1.longValue(), num2.longValue());
            case DCMPL, DCMPG ->
                // no NaN handling, could affect results
                    Double.compare(num1.longValue(), num2.longValue());
            default -> null;
        };
    }

    @Override
    public ConstantValue ternaryOperation(AbstractInsnNode insn, ConstantValue a, ConstantValue b, ConstantValue c) {
        // basic analyzer returns null, so no need for basic
        // .ternaryOperation here
        // only array store instructions here
        if (a.isKnown() && b.isKnown() && c.isKnown() && c.value instanceof Number value) {
            Object array = a.value;
            int index = b.getAsInteger();
            if (index < 0 || index >= Array.getLength(array)) {
                // avoid OOB exceptions
                return null;
            }
            switch (insn.getOpcode()) {
                case BASTORE:
                    if (array instanceof byte[]) {
                        ((byte[]) array)[index] = value.byteValue();
                    } else {
                        ((boolean[]) array)[index] = value.intValue() != 0;
                    }
                    break;
                case CASTORE:
                    ((char[]) array)[index] = (char) value.intValue();
                    break;
                case SASTORE:
                    ((short[]) array)[index] = value.shortValue();
                    break;
                case IASTORE:
                    ((int[]) array)[index] = value.intValue();
                    break;
                case FASTORE:
                    ((float[]) array)[index] = value.floatValue();
                    break;
                case DASTORE:
                    ((double[]) array)[index] = value.doubleValue();
                    break;
                case LASTORE:
                    ((long[]) array)[index] = value.longValue();
                    break;
                default:
                    return null;
            }
        }
        return null;
    }

    @Override
    public ConstantValue naryOperation(AbstractInsnNode insn, List<? extends ConstantValue> values)
            throws AnalyzerException {
        BasicValue v = basic.naryOperation(insn, null); // values unused
        // by BasicInterpreter
        switch (insn.getOpcode()) {
            case INVOKEVIRTUAL:
            case INVOKESTATIC:
            case INVOKESPECIAL:
            case INVOKEINTERFACE:
                MethodInsnNode min = (MethodInsnNode) insn;
                return v == null ? null :
                        new ConstantValue(v, referenceHandler.getMethodReturnOrNull(v, min.owner, min.name, min.desc, values));

            // TODO how to handle invokedynamic here?
            default:
                return v == null ? null : new ConstantValue(v, null);
        }
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, ConstantValue value, ConstantValue expected) {
    }

    @Override
    public ConstantValue merge(ConstantValue a, ConstantValue b) {
        if (a.equals(b))
            return a;
        BasicValue t = basic.merge(a.getType(), b.getType());

        return new ConstantValue(t, a.value);
        //		return t.equals(a.getType()) && (a.value == null && a != NULL || a.value != null && a.value.equals(b
        //		.value)) ? a : t.equals(b.getType()) && b.value == null && b != NULL ? b : new ConstantValue(t, null);
    }
}
