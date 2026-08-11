package club.mcqi.macesurvival.combat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class EquipmentBundlePayloadCodec {
    static final int MAX_SERIALIZED_ITEM_BYTES = 1_048_576;

    private EquipmentBundlePayloadCodec() {
    }

    static byte[] encode(List<byte[]> items) {
        if (items.size() > EquipmentBundleRules.CAPACITY) {
            throw new IllegalArgumentException("Equipment bundle exceeds capacity");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(items.size());
                for (byte[] item : items) {
                    validateLength(item.length);
                    output.writeInt(item.length);
                    output.write(item);
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode equipment bundle", exception);
        }
    }

    static List<byte[]> decode(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int count = input.readInt();
            if (count < 0 || count > EquipmentBundleRules.CAPACITY) {
                throw new IOException("Invalid equipment count " + count);
            }
            List<byte[]> items = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int length = input.readInt();
                validateLength(length);
                byte[] item = input.readNBytes(length);
                if (item.length != length) {
                    throw new EOFException("Truncated serialized item");
                }
                items.add(item);
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing equipment data");
            }
            return List.copyOf(items);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private static void validateLength(int length) {
        if (length < 1 || length > MAX_SERIALIZED_ITEM_BYTES) {
            throw new IllegalArgumentException("Invalid serialized item length " + length);
        }
    }
}
