package club.mcqi.macesurvival.combat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EquipmentBundlePayloadCodecTest {
    @Test
    void payloadRoundTripsInInsertionOrder() throws IOException {
        List<byte[]> source = List.of(
            new byte[] {1, 2, 3},
            new byte[] {9},
            new byte[] {4, 5, 6, 7}
        );

        List<byte[]> decoded = EquipmentBundlePayloadCodec.decode(
            EquipmentBundlePayloadCodec.encode(source)
        );

        assertEquals(source.size(), decoded.size());
        for (int index = 0; index < source.size(); index++) {
            assertArrayEquals(source.get(index), decoded.get(index));
        }
    }

    @Test
    void encoderRejectsMoreThanTwelveItemsAndEmptyItemPayloads() {
        List<byte[]> overCapacity = new ArrayList<>();
        for (int index = 0; index <= EquipmentBundleRules.CAPACITY; index++) {
            overCapacity.add(new byte[] {(byte) index});
        }

        assertThrows(IllegalArgumentException.class,
            () -> EquipmentBundlePayloadCodec.encode(overCapacity));
        assertThrows(IllegalArgumentException.class,
            () -> EquipmentBundlePayloadCodec.encode(List.of(new byte[0])));
    }

    @Test
    void decoderRejectsInvalidCountsAndLengths() {
        byte[] tooManyItems = ByteBuffer.allocate(Integer.BYTES)
            .putInt(EquipmentBundleRules.CAPACITY + 1)
            .array();
        byte[] negativeLength = ByteBuffer.allocate(Integer.BYTES * 2)
            .putInt(1)
            .putInt(-1)
            .array();
        byte[] oversizedLength = ByteBuffer.allocate(Integer.BYTES * 2)
            .putInt(1)
            .putInt(EquipmentBundlePayloadCodec.MAX_SERIALIZED_ITEM_BYTES + 1)
            .array();

        assertThrows(IOException.class, () -> EquipmentBundlePayloadCodec.decode(tooManyItems));
        assertThrows(IOException.class, () -> EquipmentBundlePayloadCodec.decode(negativeLength));
        assertThrows(IOException.class, () -> EquipmentBundlePayloadCodec.decode(oversizedLength));
    }

    @Test
    void decoderRejectsTruncatedOrTrailingData() {
        byte[] truncated = ByteBuffer.allocate(Integer.BYTES * 2 + 2)
            .putInt(1)
            .putInt(3)
            .put(new byte[] {1, 2})
            .array();
        byte[] valid = EquipmentBundlePayloadCodec.encode(List.of(new byte[] {7}));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        trailing[trailing.length - 1] = 99;

        assertThrows(IOException.class, () -> EquipmentBundlePayloadCodec.decode(truncated));
        assertThrows(IOException.class, () -> EquipmentBundlePayloadCodec.decode(trailing));
    }
}
