/*
 *
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1999.
 *
 * The Initial Developer of the Original Code is
 * Netscape Communications Corporation.
 * Portions created by the Initial Developer are Copyright (C) 1997-1999
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Bob Jervis
 *   Google Inc.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * the GNU General Public License Version 2 or later (the "GPL"), in which
 * case the provisions of the GPL are applicable instead of those above. If
 * you wish to allow use of your version of this file only under the terms of
 * the GPL and not to allow others to use your version of this file under the
 * MPL, indicate your decision by deleting the provisions above and replacing
 * them with the notice and other provisions required by the GPL. If you do
 * not delete the provisions above, a recipient may use your version of this
 * file under either the MPL or the GPL.
 *
 * ***** END LICENSE BLOCK ***** */

package com.google.javascript.rhino.jstype;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.base.JSCompObjects.identical;

import com.google.common.collect.ImmutableList;
import com.google.javascript.rhino.ErrorReporter;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The object type represents instances of JavaScript objects such as
 * {@code Object}, {@code Date}, {@code Function}.<p>
 *
 * Objects in JavaScript are unordered collections of properties.
 * Each property consists of a name, a value and a set of attributes.<p>
 *
 * Each instance has an implicit prototype property ({@code [[Prototype]]})
 * pointing to an object instance, which itself has an implicit property, thus
 * forming a chain.<p>
 *
 * A class begins life with no name.  Later, a name may be provided once it
 * can be inferred.  Note that the name in this case is strictly for
 * debugging purposes.  Looking up type name references goes through the
 * {@link JSTypeRegistry}.<p>
 */
public class PrototypeObjectType extends ObjectType {
  private static final JSTypeClass TYPE_CLASS = JSTypeClass.PROTOTYPE_OBJECT;

  private final String className;
  private final int templateParamCount;
  private final PropertyMap properties = new PropertyMap();
  private final boolean nativeType;
  private final boolean anonymousType;

  // NOTE(nicksantos): The implicit prototype can change over time.
  // Modeling this is a bear. Always call getImplicitPrototype(), because
  // some subclasses override this to do special resolution handling.
  private ObjectType implicitPrototypeFallback;

  // If this is a function prototype, then this is the owner.
  // A PrototypeObjectType can only be the prototype of one function. If we try
  // to do this for multiple functions, then we'll have to create a new one.
  private @Nullable FunctionType ownerFunction = null;

  // Whether the toString representation of this should be pretty-printed,
  // by printing all properties.
  private boolean prettyPrint = false;

  private static final int MAX_PRETTY_PRINTED_PROPERTIES = 10;

  /**
   * Creates an object type, allowing specification of the implicit prototype, whether the object is
   * native, and any templatized types.
   */
  PrototypeObjectType(Builder<?> builder) {
    super(builder.registry, builder.templateTypeMap);

    this.className = builder.className;
    this.templateParamCount = builder.templateParamCount;
    this.nativeType = builder.nativeType;
    this.anonymousType = builder.anonymousType;

    this.properties.setParentSource(this);

    if (this.nativeType || builder.implicitPrototype != null) {
      this.setImplicitPrototype(builder.implicitPrototype);
    } else {
      this.setImplicitPrototype(registry.getNativeObjectType(JSTypeNative.OBJECT_TYPE));
    }

    if (this.anonymousType) {
      checkState(this.className == null);
    }
    checkNotNull(this.templateTypeMap);
    // Also guarantees `templateParamCount >= 0`.
    checkState(this.templateTypeMap.size() >= this.templateParamCount);

    registry.getResolver().resolveIfClosed(this, TYPE_CLASS);
  }

  @Override
  JSTypeClass getTypeClass() {
    return TYPE_CLASS;
  }

  static class Builder<T extends Builder<T>> {
    final JSTypeRegistry registry;

    private String className;
    private ObjectType implicitPrototype;

    private boolean nativeType;
    private boolean anonymousType;

    private TemplateTypeMap templateTypeMap;
    private int templateParamCount;

    Builder(JSTypeRegistry registry) {
      this.registry = registry;

      this.templateTypeMap = registry.getEmptyTemplateTypeMap();
    }

    T setName(String x) {
      this.className = x;
      return castThis();
    }

    final T setImplicitPrototype(ObjectType x) {
      this.implicitPrototype = x;
      return castThis();
    }

    final T setNative(boolean x) {
      this.nativeType = x;
      return castThis();
    }

    final T setAnonymous(boolean x) {
      this.anonymousType = x;
      return castThis();
    }

    final T setTemplateTypeMap(TemplateTypeMap x) {
      this.templateTypeMap = x;
      return castThis();
    }

    final T setTemplateParamCount(int x) {
      this.templateParamCount = x;
      return castThis();
    }

    @SuppressWarnings("unchecked")
    final T castThis() {
      return (T) this;
    }

    PrototypeObjectType build() {
      return new PrototypeObjectType(this);
    }
  }

  static Builder<?> builder(JSTypeRegistry registry) {
    return new Builder<>(registry);
  }

  @Override
  PropertyMap getPropertyMap() {
    return properties;
  }

  @Override
  boolean defineProperty(Property.Key name, JSType type, boolean inferred, Node propertyNode) {
    if (hasOwnDeclaredProperty(name)) {
      return false;
    }
    Property newProp = new Property(name, type, inferred, propertyNode);
    properties.putProperty(name, newProp);
    return true;
  }

  @Override
  public void setPropertyJSDocInfo(Property.Key propertyName, JSDocInfo info) {
    if (info != null) {
      if (properties.getOwnProperty(propertyName) == null) {
        // If docInfo was attached, but the type of the property
        // was not defined anywhere, then we consider this an explicit
        // declaration of the property.
        defineInferredProperty(propertyName, getPropertyType(propertyName),
            null);
      }

      // The prototype property is not represented as a normal Property.
      // We probably don't want to attach any JSDoc to it anyway.
      Property property = properties.getOwnProperty(propertyName);
      if (property != null) {
        property.setJSDocInfo(info);
      }
    }
  }

  @Override
  public void setPropertyNode(Property.Key propertyName, Node defSite) {
    Property property = properties.getOwnProperty(propertyName);
    if (property != null) {
      property.setNode(defSite);
    }
  }

  @Override
  public boolean matchesNumberContext() {
    // BigInt is intentionally left out here. It cannot be coerced to a Number.
    return isNumberObjectType()
        || isDateType()
        || isBooleanObjectType()
        || isStringObjectType()
        || hasOverriddenNativeProperty("valueOf");
  }

  @Override
  public boolean matchesStringContext() {
    return isTheObjectType()
        || isStringObjectType()
        || isDateType()
        || isRegexpType()
        || isArrayType()
        || isNumberObjectType()
        || isBigIntObjectType()
        || isBooleanObjectType()
        || hasOverriddenNativeProperty("toString");
  }

  @Override
  public boolean matchesSymbolContext() {
    return isSymbolObjectType();
  }

  /**
   * Given the name of a native object property, checks whether the property is
   * present on the object and different from the native one.
   */
  private boolean hasOverriddenNativeProperty(String propertyName) {
    if (isNativeObjectType()) {
      return false;
    }

    JSType propertyType = getPropertyType(propertyName);
    ObjectType nativeType =
        isFunctionType()
            ? registry.getNativeObjectType(JSTypeNative.FUNCTION_PROTOTYPE)
            : registry.getNativeObjectType(JSTypeNative.OBJECT_PROTOTYPE);
    JSType nativePropertyType = nativeType.getPropertyType(propertyName);
    return !identical(propertyType, nativePropertyType);
  }

  @Override
  public boolean matchesObjectContext() {
    return true;
  }

  private record PropertyForPrettyPrinting(Property.Key key, JSType type) {
    private void appendTo(TypeStringBuilder sb) {
      switch (key.kind()) {
        case STRING:
          sb.append(key.humanReadableName());
          break;
        case SYMBOL:
          sb.append("[");
          sb.append(key.humanReadableName());
          sb.append("]");
          break;
      }
      sb.append(": ").appendNonNull(type);
    }
  }

  private static final Comparator<PropertyForPrettyPrinting> PROP_COMPARATOR =
      (thisProp, otherProp) -> {
        // Handle the easy case where the property names are distinct.
        if (!thisProp.key().humanReadableName().equals(otherProp.key().humanReadableName())) {
          return thisProp.key().humanReadableName().compareTo(otherProp.key().humanReadableName());
        }
        return switch (thisProp.key().kind()) {
          case STRING ->
              switch (otherProp.key().kind()) {
                case STRING -> thisProp.key().string().compareTo(otherProp.key().string());
                case SYMBOL -> -1; // order string keys before symbol keys
              };
          case SYMBOL ->
              switch (otherProp.key().kind()) {
                case STRING -> 1; // order string keys before symbol keys.
                case SYMBOL ->
                    // Given two symbol keys with the same human-readable name, compare them by
                    // their type string. This should be rare, so it's not a big performance hit to
                    // do the extra toString()s.
                    thisProp.type().toString().compareTo(otherProp.type().toString());
              };
        };
      };

  @Override
  void appendTo(TypeStringBuilder sb) {
    if (hasReferenceName()) {
      sb.append(sb.isForAnnotations() ? getNormalizedReferenceName() : getReferenceName());
      return;
    }

    if (!this.prettyPrint) {
      sb.append(sb.isForAnnotations() ? "?" : "{...}");
      return;
    }

    // Don't pretty print recursively. It would cause infinite recursion.
    this.prettyPrint = false;

    // Use a tree set so that the properties are sorted.
    Set<PropertyForPrettyPrinting> properties = new TreeSet<>(PROP_COMPARATOR);
    for (ObjectType current = this; current != null; current = current.getImplicitPrototype()) {
      if (current.isNativeObjectType() || properties.size() > MAX_PRETTY_PRINTED_PROPERTIES) {
        break;
      }

      for (String stringKey : current.getOwnPropertyNames()) {
        var key = new Property.StringKey(stringKey);
        properties.add(new PropertyForPrettyPrinting(key, current.getPropertyType(key)));
      }
      for (KnownSymbolType symbolKey : current.getOwnPropertyKnownSymbols()) {
        var key = new Property.SymbolKey(symbolKey);
        properties.add(new PropertyForPrettyPrinting(key, current.getPropertyType(key)));
      }
    }

    boolean multiline = !sb.isForAnnotations() && properties.size() > 1;
    sb.append("{")
        .indent(
            () -> {
              if (multiline) {
                sb.breakLineAndIndent();
              }

              int i = 0;
              for (PropertyForPrettyPrinting property : properties) {
                i++;

                if (!sb.isForAnnotations() && i > MAX_PRETTY_PRINTED_PROPERTIES) {
                  sb.append("...");
                  break;
                }

                property.appendTo(sb);
                if (i < properties.size()) {
                  sb.append(",");
                  if (multiline) {
                    sb.breakLineAndIndent();
                  } else {
                    sb.append(" ");
                  }
                }
              }
            });
    if (multiline) {
      sb.breakLineAndIndent();
    }
    sb.append("}");

    this.prettyPrint = true;
  }

  void setPrettyPrint(boolean prettyPrint) {
    this.prettyPrint = prettyPrint;
  }

  boolean isPrettyPrint() {
    return prettyPrint;
  }

  @Override
  public FunctionType getConstructor() {
    return null;
  }

  @Override
  public ObjectType getImplicitPrototype() {
    return implicitPrototypeFallback;
  }

  /**
   * This should only be reset on the FunctionPrototypeType, only to fix an incorrectly established
   * prototype chain due to the user having a mismatch in super class declaration, and only before
   * properties on that type are processed.
   */
  final void setImplicitPrototype(ObjectType implicitPrototype) {
    checkState(!hasCachedValues());
    this.implicitPrototypeFallback = implicitPrototype;
    if (implicitPrototype != null) {
      maybeLoosenTypecheckingDueToForwardReferencedSupertype(implicitPrototype);
    }
  }

  @Override
  public final int getTemplateParamCount() {
    return this.templateParamCount;
  }

  @Override
  public @Nullable String getReferenceName() {
    if (className != null) {
      return className;
    } else if (ownerFunction != null) {
      return ownerFunction.getReferenceName() + ".prototype";
    } else {
      return null;
    }
  }

  public boolean isAnonymous() {
    return anonymousType;
  }

  /** Whether this is a built-in object. */
  @Override
  public boolean isNativeObjectType() {
    return nativeType;
  }

  @Override
  void setOwnerFunction(FunctionType type) {
    checkState(ownerFunction == null || type == null);
    ownerFunction = type;
  }

  @Override
  public FunctionType getOwnerFunction() {
    return ownerFunction;
  }

  @Override
  public Iterable<ObjectType> getCtorImplementedInterfaces() {
    return isFunctionPrototypeType()
        ? getOwnerFunction().getImplementedInterfaces()
        : ImmutableList.<ObjectType>of();
  }

  @Override
  public Iterable<ObjectType> getCtorExtendedInterfaces() {
    return isFunctionPrototypeType()
        ? getOwnerFunction().getExtendedInterfaces()
        : ImmutableList.<ObjectType>of();
  }

  @Override
  JSType resolveInternal(ErrorReporter reporter) {
    ObjectType implicitPrototype = getImplicitPrototype();
    if (implicitPrototype != null) {
      implicitPrototypeFallback = (ObjectType) implicitPrototype.resolve(reporter);
    }
    for (Property prop : properties.values()) {
      prop.setType(safeResolve(prop.getType(), reporter));
    }
    return this;
  }

  @Override
  public void matchConstraint(JSType constraint) {
    // We only want to match constraints on anonymous types.
    if (hasReferenceName()) {
      return;
    }

    // Handle the case where the constraint object is a record type.
    //
    // param constraint {{prop: (number|undefined)}}
    // function f(constraint) {}
    // f({});
    //
    // We want to modify the object literal to match the constraint, by
    // taking any each property on the record and trying to match
    // properties on this object.
    if (constraint.isRecordType()) {
      matchRecordTypeConstraint(constraint.toObjectType());
    } else if (constraint.isUnionType()) {
      for (JSType alt : constraint.toMaybeUnionType().getAlternates()) {
        if (alt.isRecordType()) {
          matchRecordTypeConstraint(alt.toObjectType());
        }
      }
    }
  }

  public void matchRecordTypeConstraint(ObjectType constraintObj) {
    for (String prop : constraintObj.getOwnPropertyNames()) {
      JSType propType = constraintObj.getPropertyType(prop);
      if (!isPropertyTypeDeclared(prop)) {
        JSType typeToInfer = propType;
        if (!hasProperty(prop)) {
          typeToInfer = getNativeType(JSTypeNative.VOID_TYPE)
              .getLeastSupertype(propType);
        }
        defineInferredProperty(prop, typeToInfer, null);
      }
    }
  }

  @Override
  int recursionUnsafeHashCode() {
    if (isStructuralType()) {
      return Objects.hash(className, properties);
    } else {
      return System.identityHashCode(this);
    }
  }
}
