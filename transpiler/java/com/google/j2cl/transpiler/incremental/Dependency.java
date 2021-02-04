package com.google.j2cl.transpiler.incremental;

public class Dependency {
    private TypeInfo   caller;
    private TypeInfo   callee;
    private MemberInfo memberInfo;

    public Dependency(TypeInfo caller, TypeInfo callee, MemberInfo memberInfo) {
        this.caller = caller;
        this.callee = callee;
        this.memberInfo = memberInfo;
        boolean b1 = caller.addOutgoingDependency(this);
        boolean b2 = callee.addIncomingDependency(this);

    }

    public TypeInfo getCallee() {
        return callee;
    }

    public TypeInfo getCaller() {
        return caller;
    }

    public MemberInfo getMemberInfo() {
        return memberInfo;
    }

    public void remove() {
        callee.getIncomingDependencies().remove(this);
        caller.getOutgoingDependencies().remove(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Dependency that = (Dependency) o;

        if (!caller.equals(that.caller)) {
            return false;
        }
        if (!callee.equals(that.callee)) {
            return false;
        }
        return memberInfo.equals(that.memberInfo);
    }

    @Override
    public int hashCode() {
        int result =  caller.hashCode();
        result = 31 * result + callee.hashCode();
        result = 31 * result + memberInfo.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Dependency{" +
               "caller=" + caller +
               ", callee=" + callee +
               ", memberInfo=" + memberInfo +
               '}';
    }

    public ImpactingState getCalleeImpactingState() {
        return memberInfo.getImpactingState();
    }
}
