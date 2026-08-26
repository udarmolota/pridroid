package com.pridroid.xserver.requests;

import static com.pridroid.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.pridroid.xconnector.XInputStream;
import com.pridroid.xconnector.XOutputStream;
import com.pridroid.xconnector.XStreamLock;
import com.pridroid.xserver.Atom;
import com.pridroid.xserver.XClient;
import com.pridroid.xserver.errors.BadAtom;
import com.pridroid.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class AtomRequests {
    public static void internAtom(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean onlyIfExists = client.getRequestData() == 1;
        short length = inputStream.readShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        int id = onlyIfExists ? Atom.getId(name) : Atom.internAtom(name);
        if (id < 0) throw new BadAtom(id);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(id);
            outputStream.writePad(20);
        }
    }

    public static void getAtomName(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int id = inputStream.readInt();
        if (id < 0) throw new BadAtom(id);

        String name = Atom.getName(id);
        int length = name.length();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + (-length & 3)) / 4);
            outputStream.writeShort((short)length);
            outputStream.writePad(22);
            outputStream.writeString8(name);
        }
    }
}