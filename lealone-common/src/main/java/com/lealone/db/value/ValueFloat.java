/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.value;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.DataUtils;
import com.lealone.db.DataBuffer;
import com.lealone.db.api.ErrorCode;

/**
 * Implementation of the REAL data type.
 */
public class ValueFloat extends Value {

    /**
     * Float.floatToIntBits(0.0F).
     */
    public static final int ZERO_BITS = Float.floatToIntBits(0.0F);

    /**
     * The precision in digits.
     */
    static final int PRECISION = 7;

    /**
     * The maximum display size of a float.
     * Example: -1.12345676E-20
     */
    static final int DISPLAY_SIZE = 15;

    private static final ValueFloat ZERO = new ValueFloat(0.0F);
    private static final ValueFloat ONE = new ValueFloat(1.0F);

    private final float value;

    private ValueFloat(float value) {
        this.value = value;
    }

    @Override
    public Value add(Value v) {
        ValueFloat v2 = (ValueFloat) v;
        return ValueFloat.get(value + v2.value);
    }

    @Override
    public Value subtract(Value v) {
        ValueFloat v2 = (ValueFloat) v;
        return ValueFloat.get(value - v2.value);
    }

    @Override
    public Value negate() {
        return ValueFloat.get(-value);
    }

    @Override
    public Value multiply(Value v) {
        ValueFloat v2 = (ValueFloat) v;
        return ValueFloat.get(value * v2.value);
    }

    @Override
    public Value divide(Value v) {
        ValueFloat v2 = (ValueFloat) v;
        if (v2.value == 0.0) {
            throw DbException.get(ErrorCode.DIVISION_BY_ZERO_1, getSQL());
        }
        return ValueFloat.get(value / v2.value);
    }

    @Override
    public Value modulus(Value v) {
        ValueFloat other = (ValueFloat) v;
        if (other.value == 0) {
            throw DbException.get(ErrorCode.DIVISION_BY_ZERO_1, getSQL());
        }
        return ValueFloat.get(value % other.value);
    }

    @Override
    public String getSQL() {
        if (value == Float.POSITIVE_INFINITY) {
            return "POWER(0, -1)";
        } else if (value == Float.NEGATIVE_INFINITY) {
            return "(-POWER(0, -1))";
        } else if (Double.isNaN(value)) {
            // NaN
            return "SQRT(-1)";
        }
        String s = getString();
        if (s.equals("-0.0")) {
            return "-CAST(0 AS REAL)";
        }
        return s;
    }

    @Override
    public int getType() {
        return Value.FLOAT;
    }

    @Override
    protected int compareSecure(Value o, CompareMode mode) {
        ValueFloat v = (ValueFloat) o;
        return Float.compare(value, v.value);
    }

    @Override
    public int getSignum() {
        return value == 0 ? 0 : (value < 0 ? -1 : 1);
    }

    @Override
    public float getFloat() {
        return value;
    }

    @Override
    public String getString() {
        return String.valueOf(value);
    }

    @Override
    public long getPrecision() {
        return PRECISION;
    }

    @Override
    public int getScale() {
        return 0;
    }

    @Override
    public int hashCode() {
        long hash = Float.floatToIntBits(value);
        return (int) (hash ^ (hash >> 32));
    }

    @Override
    public Object getObject() {
        return Float.valueOf(value);
    }

    @Override
    public void set(PreparedStatement prep, int parameterIndex) throws SQLException {
        prep.setFloat(parameterIndex, value);
    }

    /**
     * Get or create float value for the given float.
     *
     * @param d the float
     * @return the value
     */
    public static ValueFloat get(float d) {
        if (d == 1.0F) {
            return ONE;
        } else if (d == 0.0F) {
            // unfortunately, -0.0 == 0.0, but we don't want to return
            // 0.0 in this case
            if (Float.floatToIntBits(d) == ZERO_BITS) {
                return ZERO;
            }
        }
        return (ValueFloat) Value.cache(new ValueFloat(d));
    }

    @Override
    public int getDisplaySize() {
        return DISPLAY_SIZE;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ValueFloat)) {
            return false;
        }
        return compareSecure((ValueFloat) other, null) == 0;
    }

    @Override
    public int getMemory() {
        return 16;
    }

    public static final ValueDataTypeBase type = new ValueDataTypeBase() {

        @Override
        public int getType() {
            return FLOAT;
        }

        @Override
        public int compare(Object aObj, Object bObj) {
            Float a = (Float) aObj;
            Float b = (Float) bObj;
            return a.compareTo(b);
        }

        @Override
        public int getMemory(Object obj) {
            return 16;
        }

        @Override
        public void write(DataBuffer buff, Object obj) {
            float x = (Float) obj;
            write0(buff, x);
        }

        @Override
        public void writeValue(DataBuffer buff, Value v) {
            float x = v.getFloat();
            write0(buff, x);
        }

        private void write0(DataBuffer buff, float x) {
            int f = Float.floatToIntBits(x);
            if (f == FLOAT_ZERO_BITS) {
                buff.put((byte) TAG_FLOAT_0);
            } else if (f == FLOAT_ONE_BITS) {
                buff.put((byte) TAG_FLOAT_1);
            } else {
                int value = Integer.reverse(f);
                if (value >= 0 && value <= DataUtils.COMPRESSED_VAR_INT_MAX) {
                    buff.put((byte) FLOAT).putVarInt(value);
                } else {
                    buff.put((byte) TAG_FLOAT_FIXED).putFloat(x);
                }
            }
        }

        @Override
        public Value readValue(ByteBuffer buff, int tag) {
            switch (tag) {
            case TAG_FLOAT_0:
                return ValueFloat.get(0f);
            case TAG_FLOAT_1:
                return ValueFloat.get(1f);
            case TAG_FLOAT_FIXED:
                return ValueFloat.get(buff.getFloat());
            }
            return ValueFloat.get(Float.intBitsToFloat(Integer.reverse(DataUtils.readVarInt(buff))));
        }
    };
}
