package com.google.devtools.build.lib.remote.zstd;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.base.Preconditions;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class ZstdCompressingInputStream extends FilterInputStream {
    // We want the buffer to be able to contain at least:
    //   - Magic number: 4 bytes
    //   - FrameHeader 14 bytes
    //   - Block Header: 3 bytes
    //   - First block byte
    // This guarantees that we can always compress at least
    // 1 byte and write it to the pipe without blocking.
    static public int MIN_BUFFER_SIZE = 4 + 14 + 3 + 1;

    private final PipedInputStream pis;
    private ZstdOutputStream zos;
    private final int size;

    public ZstdCompressingInputStream(InputStream in) throws IOException {
        this(in, 512);
    }

    ZstdCompressingInputStream(InputStream in, int size) throws IOException {
        super(in);
        Preconditions.checkArgument(
                size >= MIN_BUFFER_SIZE,
                String.format("The buffer size must be at least %d bytes", MIN_BUFFER_SIZE));
        this.size = size;
        this.pis = new PipedInputStream(size);
        this.zos = new ZstdOutputStream(new PipedOutputStream(pis));
    }

    private void reFill() throws IOException {
      byte[] buf = new byte[size];
      int len = super.read(buf, 0 , Math.max(0, size - pis.available() - MIN_BUFFER_SIZE + 1));
      if (len == -1) {
        zos.close();
        zos = null;
      } else {
        zos.write(buf, 0, len);
        zos.flush();
      }
    }

    @Override
    public int read() throws IOException {
        if (pis.available() == 0) {
            if (zos == null) {
                return -1;
            }
            reFill();
        }
        return pis.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int count = 0;
        int n = len > 0 ? -1 : 0;
        while (count < len && (pis.available() > 0 || zos != null)) {
            if (pis.available() == 0) {
                reFill();
            }
            n = pis.read(b, count + off, len - count);
            count += Math.max(0, n);
        }
        return count > 0 ? count : n;
    }

    @Override
    public void close() throws IOException {
        if (zos != null) {
            zos.close();
        }
        in.close();
    }
}
