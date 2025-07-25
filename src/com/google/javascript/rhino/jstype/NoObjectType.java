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

import com.google.javascript.rhino.ErrorReporter;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import org.jspecify.annotations.Nullable;

/**
 * The bottom Object type, representing the subclass of all objects.
 *
 * <p>Although JavaScript programmers can't explicitly denote the bottom Object type, it comes up in
 * static analysis. For example, if we have: <code>
 * var x = function() {};
 * if (x instanceof Array) {
 *   f(x);
 * }
 * </code> We need to be able to assign {@code x} a type within the {@code f(x)} call. It has no
 * possible type, but {@code x} would not be legal if f expected a string. So we assign it the
 * {@code NoObjectType}.
 *
 * @see <a href="http://en.wikipedia.org/wiki/Bottom_type">Bottom types</a>
 */
public class NoObjectType extends FunctionType {
  NoObjectType(JSTypeRegistry registry) {
    super(
        FunctionType.builder(registry)
            .withKind(FunctionType.Kind.NONE)
            .withReturnsOwnInstanceType()
            .forNativeType());
    this.eagerlyResolveToSelf();
  }

  @Override
  JSTypeClass getTypeClass() {
    return JSTypeClass.NO_OBJECT;
  }

  @Override
  public final @Nullable FunctionType toMaybeFunctionType() {
    return null;
  }

  @Override
  public boolean isNoObjectType() {
    return true;
  }

  @Override
  public final @Nullable ObjectType getImplicitPrototype() {
    return null;
  }

  @Override
  public String getReferenceName() {
    return null;
  }
  @Override
  public final boolean matchesNumberContext() {
    return true;
  }

  @Override
  public final boolean matchesObjectContext() {
    return true;
  }

  @Override
  public final boolean matchesStringContext() {
    return true;
  }

  @Override
  public final boolean matchesSymbolContext() {
    return true;
  }

  @Override
  int recursionUnsafeHashCode() {
    return System.identityHashCode(this);
  }

  @Override
  final boolean defineProperty(
      Property.Key propertyName, JSType type, boolean inferred, Node propertyNode) {
    // nothing, all properties are defined
    return true;
  }

  @Override
  public final void setPropertyJSDocInfo(Property.Key propertyName, JSDocInfo info) {
    // Do nothing, specific properties do not have JSDocInfo.
  }

  @Override
  public <T> T visit(Visitor<T> visitor) {
    return visitor.caseNoObjectType();
  }

  @Override <T> T visit(RelationshipVisitor<T> visitor, JSType that) {
    return visitor.caseNoObjectType(that);
  }

  @Override
  void appendTo(TypeStringBuilder sb) {
    sb.append(sb.isForAnnotations() ? "?" : "NoObject");
  }

  @Override
  public final @Nullable FunctionType getConstructor() {
    return null;
  }

  @Override
  final JSType resolveInternal(ErrorReporter reporter) {
    throw new AssertionError();
  }
}
