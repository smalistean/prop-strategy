package com.smalistean.propstrategy.xvf.venue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@code HyperliquidGateway}'s msgpack encoding against two vectors independently generated
 * from a from-scratch Python reference (msgpack.packb + pycryptodome keccak) built during
 * implementation from the official SDK source, not from this code.
 *
 * <p>The second vector is a regression test for a real bug caught live: an order id above 2^32
 * ({@code 519178520652}, from an actual cancel on Hyperliquid's mainnet) was silently truncated to its
 * low 32 bits by a narrowing {@code (int)} cast with no branch for {@code uint64}. The JSON body sent
 * to the exchange carried the untruncated value via Jackson, so the exchange computed a different hash
 * than the one this class signed - and a valid signature over the wrong hash still recovers a
 * mathematically valid but meaningless address, which is what turned into
 * {@code "User or API Wallet 0xa817... does not exist"} for an address that had never been configured
 * anywhere. Cancels are the first path that carries an integer this large; a fresh order never
 * exercised it, which is why this needs its own vector rather than trusting the order-placement one.
 */
class HyperliquidMsgPackTest {

    private static String encode(Map<String, Object> action) throws Exception {
        Class<?> msgPack = Class.forName(
                "com.smalistean.propstrategy.xvf.venue.HyperliquidGateway$MsgPack");
        Method write = msgPack.getDeclaredMethod("write", Object.class, ByteArrayOutputStream.class);
        write.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write.invoke(null, action, out);
        StringBuilder hex = new StringBuilder();
        for (byte b : out.toByteArray()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void orderActionWithSmallIntegersMatchesThePythonReference() throws Exception {
        Map<String, Object> orderWire = new LinkedHashMap<>();
        orderWire.put("a", 267);
        orderWire.put("b", false);
        orderWire.put("p", "0.009942");
        orderWire.put("s", "25145");
        orderWire.put("r", false);
        Map<String, Object> tif = new LinkedHashMap<>();
        tif.put("tif", "Alo");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("limit", tif);
        orderWire.put("t", t);
        orderWire.put("c", "0x00112233445566778899aabbccddeeff");

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "order");
        action.put("orders", List.of(orderWire));
        action.put("grouping", "na");

        assertEquals("83a474797065a56f72646572a66f72646572739187a161cd010ba162c2a170a8302e303039393432a173a53235313435a172c2a17481a56c696d697481a3746966a3416c6fa163d92230783030313132323333343435353636373738383939616162626363646465656666a867726f7570696e67a26e61",
                encode(action), "must byte-for-byte match Python's msgpack.packb");
    }

    @Test
    void cancelActionWithAnOrderIdAbove32BitsUsesUint64NotTruncation() throws Exception {
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("a", 0);
        cancel.put("o", 519178520652L);   // the real oid from the live cancel that first hit this bug
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "cancel");
        action.put("cancels", List.of(cancel));

        // 0xcf = uint64 marker, followed by the full 8-byte big-endian value. Before the fix this
        // fell into the uint32 branch and silently emitted 4 truncated bytes instead.
        assertEquals("82a474797065a663616e63656ca763616e63656c739182a16100a16fcf00000078e173884c",
                encode(action), "an oid above 2^32 must encode as uint64, not be truncated");
    }
}
