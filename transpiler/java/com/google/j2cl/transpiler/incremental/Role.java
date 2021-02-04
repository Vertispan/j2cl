package com.google.j2cl.transpiler.incremental;

public enum Role {
    // METHOD and FIELD may have wrapper text at the useby point.
    METHOD, CONSTRUCTOR, FIELD, CLASS, SUPER;
}
