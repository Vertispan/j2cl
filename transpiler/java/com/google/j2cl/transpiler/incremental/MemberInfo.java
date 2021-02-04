package com.google.j2cl.transpiler.incremental;

public class MemberInfo {

    private Role role;

    private String         enclosingType;
    private String         name;
    private String         signature;
    private String         returnType;
    private ImpactingState impacting;

    public MemberInfo(Role role, String enclosingType, String name, String signature, String returnType, ImpactingState impacting) {
        this.role = role;
        this.enclosingType = enclosingType;
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
        this.impacting = impacting;
    }

    public MemberInfo(Role memberRole, String enclosingType, String name, String signature, String returnType) {
        this(memberRole, enclosingType, name, signature, returnType, ImpactingState.UNSET);
    }

    public Role getRole() {
        return role;
    }

    public String getEnclosingType() {
        return enclosingType;
    }

    public String getName() {
        return name;
    }

    public String getSignature() {
        return signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public ImpactingState getImpactingState() {
        return impacting;
    }

    @Override public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MemberInfo that = (MemberInfo) o;

        if (role != that.role) {
            return false;
        }
        if (!enclosingType.equals(that.enclosingType)) {
            return false;
        }
        if (!name.equals(that.name)) {
            return false;
        }
        if (!signature.equals(that.signature)) {
            return false;
        }
        return returnType.equals(that.returnType);
    }

    @Override public int hashCode() {
        int result = role.hashCode();
        result = 31 * result + enclosingType.hashCode();
        result = 31 * result + name.hashCode();
        result = 31 * result + signature.hashCode();
        result = 31 * result + returnType.hashCode();
        return result;
    }

    @Override public String toString() {
        return "MemberInfo{" +
               "memberRole=" + role +
               ", enclosingType='" + enclosingType + '\'' +
               ", name='" + name + '\'' +
               ", signature='" + signature + '\'' +
               ", returnType='" + returnType + '\'' +
               ", impacting=" + impacting +
               '}';
    }

    public void update(MemberInfo other) {
        this.impacting = other.impacting;
    }

    public MemberInfo clone() {
        return new MemberInfo(role, enclosingType, name, signature, returnType, impacting);
    }
}
