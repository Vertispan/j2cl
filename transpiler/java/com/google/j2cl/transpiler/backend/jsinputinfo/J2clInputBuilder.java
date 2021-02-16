package com.google.j2cl.transpiler.backend.jsinputinfo;

import com.google.j2cl.common.Problems;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;

public class J2clInputBuilder {

    public static String toJsonString(J2clInput.Builder builder, Problems problems) {
        try {
            return JsonFormat.printer().print(builder.build());
        } catch (IOException e) {
            problems.fatal(Problems.FatalError.CANNOT_WRITE_FILE, e.toString());
            return null;
        }
    }

    public static J2clInput fromJsonString(String string) throws IOException {
        J2clInput.Builder builder = J2clInput.newBuilder();
        JsonFormat.parser().merge(string, builder);
        return builder.build();
    }
}
