package cc.olek.lamada.asm;

import java.util.Objects;

public final class LambdaImpl {
    private final String functionalInterface;
    private final int implMethodKind;
    private final String primarySignature;
    private MethodImpl implementation;

    public LambdaImpl(String functionalInterface, int implMethodKind, String primarySignature, MethodImpl implementation) {
        this.functionalInterface = functionalInterface;
        this.implMethodKind = implMethodKind;
        this.primarySignature = primarySignature;
        this.implementation = implementation;
    }

    public String functionalInterface() {
        return functionalInterface;
    }

    public int implMethodKind() {
        return implMethodKind;
    }

    public String primarySignature() {
        return primarySignature;
    }

    public MethodImpl implementation() {
        return implementation;
    }

    public void setImplementation(MethodImpl implementation) {
        this.implementation = implementation;
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this) return true;
        if(obj == null || obj.getClass() != this.getClass()) return false;
        var that = (LambdaImpl) obj;
        return Objects.equals(this.functionalInterface, that.functionalInterface) &&
            this.implMethodKind == that.implMethodKind &&
            Objects.equals(this.primarySignature, that.primarySignature) &&
            Objects.equals(this.implementation, that.implementation);
    }

    @Override
    public int hashCode() {
        return implementation.hashCode();
    }

    @Override
    public String toString() {
        return "LambdaImpl[" +
            "functionalInterface=" + functionalInterface + ", " +
            "implMethodKind=" + implMethodKind + ", " +
            "primarySignature=" + primarySignature + ", " +
            "implementation=" + implementation + ']';
    }

    @Override
    public LambdaImpl clone() {
        return new LambdaImpl(functionalInterface, implMethodKind, primarySignature, implementation.clone());
    }
}
