package de.landsmapbridge.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format shared by the Fabric mod and the Paper plugin. Every message is a plain
 * plugin message on its own channel, encoded with {@link DataOutputStream}.
 */
public final class Protocol {

    public static final String NAMESPACE = "landsmapbridge";
    public static final int VERSION = 1;

    /** Client to server: {@link Hello}. Server to client: {@link Welcome}. */
    public static final String HELLO = NAMESPACE + ":hello";
    /** Server to client: {@link LandsUpdate}. */
    public static final String LANDS = NAMESPACE + ":lands";
    /** Server to client: {@link ChunksUpdate}. */
    public static final String CHUNKS = NAMESPACE + ":chunks";
    /** Client to server: {@link ClaimRequest}. */
    public static final String CLAIM = NAMESPACE + ":claim";

    /** Land id meaning "no land" in a chunk update. */
    public static final int NO_LAND = 0;

    /** Keeps single packets well below the 1 MiB custom payload limit. */
    public static final int MAX_CHUNKS_PER_PACKET = 16384;

    private Protocol() {
    }

    /** How the viewing player relates to a land. Decides the colour on the map. */
    public enum Relation {
        OWN, MEMBER, ALLY, ENEMY, NEUTRAL;

        static Relation byId(int id) {
            Relation[] values = values();
            return id >= 0 && id < values.length ? values[id] : NEUTRAL;
        }
    }

    public record Hello(int version) {
        public byte[] encode() {
            return write(out -> out.writeInt(version));
        }

        public static Hello decode(byte[] data) {
            return read(data, in -> new Hello(in.readInt()));
        }
    }

    public record Welcome(int version, int maxClaimArea) {
        public byte[] encode() {
            return write(out -> {
                out.writeInt(version);
                out.writeInt(maxClaimArea);
            });
        }

        public static Welcome decode(byte[] data) {
            return read(data, in -> new Welcome(in.readInt(), in.readInt()));
        }
    }

    public record LandInfo(int id, String name, String owner, Relation relation) {
    }

    /** Adds or replaces lands. With {@code reset} the client drops every land it knew first. */
    public record LandsUpdate(boolean reset, List<LandInfo> lands, int[] removed) {
        public byte[] encode() {
            return write(out -> {
                out.writeBoolean(reset);
                out.writeInt(lands.size());
                for (LandInfo land : lands) {
                    out.writeInt(land.id());
                    out.writeUTF(land.name());
                    out.writeUTF(land.owner());
                    out.writeByte(land.relation().ordinal());
                }
                out.writeInt(removed.length);
                for (int id : removed) {
                    out.writeInt(id);
                }
            });
        }

        public static LandsUpdate decode(byte[] data) {
            return read(data, in -> {
                boolean reset = in.readBoolean();
                int count = in.readInt();
                List<LandInfo> lands = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    lands.add(new LandInfo(in.readInt(), in.readUTF(), in.readUTF(), Relation.byId(in.readByte())));
                }
                int[] removed = new int[in.readInt()];
                for (int i = 0; i < removed.length; i++) {
                    removed[i] = in.readInt();
                }
                return new LandsUpdate(reset, lands, removed);
            });
        }
    }

    /**
     * Sets the owner of chunks in one dimension ({@link #NO_LAND} unclaims). With {@code reset}
     * the client forgets every chunk it knew in that dimension first.
     */
    public record ChunksUpdate(String dimension, boolean reset, int[] chunkX, int[] chunkZ, int[] landIds) {
        public byte[] encode() {
            return write(out -> {
                out.writeUTF(dimension);
                out.writeBoolean(reset);
                out.writeInt(landIds.length);
                for (int i = 0; i < landIds.length; i++) {
                    out.writeInt(chunkX[i]);
                    out.writeInt(chunkZ[i]);
                    out.writeInt(landIds[i]);
                }
            });
        }

        public static ChunksUpdate decode(byte[] data) {
            return read(data, in -> {
                String dimension = in.readUTF();
                boolean reset = in.readBoolean();
                int count = in.readInt();
                int[] x = new int[count];
                int[] z = new int[count];
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    x[i] = in.readInt();
                    z[i] = in.readInt();
                    ids[i] = in.readInt();
                }
                return new ChunksUpdate(dimension, reset, x, z, ids);
            });
        }
    }

    /** Claim or unclaim every chunk in the rectangle, for the land picked with /lands edit. */
    public record ClaimRequest(String dimension, boolean unclaim, int minX, int minZ, int maxX, int maxZ) {
        public byte[] encode() {
            return write(out -> {
                out.writeUTF(dimension);
                out.writeBoolean(unclaim);
                out.writeInt(minX);
                out.writeInt(minZ);
                out.writeInt(maxX);
                out.writeInt(maxZ);
            });
        }

        public static ClaimRequest decode(byte[] data) {
            return read(data, in -> new ClaimRequest(in.readUTF(), in.readBoolean(), in.readInt(), in.readInt(), in.readInt(), in.readInt()));
        }

        public long area() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static <T> T read(byte[] data, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return reader.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
