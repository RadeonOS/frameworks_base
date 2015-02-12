/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "JavaClassGenerator.h"
#include "NameMangler.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "StringPiece.h"

#include <algorithm>
#include <ostream>
#include <set>
#include <sstream>
#include <tuple>

namespace aapt {

// The number of attributes to emit per line in a Styleable array.
constexpr size_t kAttribsPerLine = 4;

JavaClassGenerator::JavaClassGenerator(const std::shared_ptr<const ResourceTable>& table,
                                       Options options) :
        mTable(table), mOptions(options) {
}

static void generateHeader(std::ostream& out, const StringPiece16& package) {
    out << "/* AUTO-GENERATED FILE. DO NOT MODIFY.\n"
           " *\n"
           " * This class was automatically generated by the\n"
           " * aapt tool from the resource data it found. It\n"
           " * should not be modified by hand.\n"
           " */\n\n";
    out << "package " << package << ";"
        << std::endl
        << std::endl;
}

static const std::set<StringPiece16> sJavaIdentifiers = {
    u"abstract", u"assert", u"boolean", u"break", u"byte",
    u"case", u"catch", u"char", u"class", u"const", u"continue",
    u"default", u"do", u"double", u"else", u"enum", u"extends",
    u"final", u"finally", u"float", u"for", u"goto", u"if",
    u"implements", u"import", u"instanceof", u"int", u"interface",
    u"long", u"native", u"new", u"package", u"private", u"protected",
    u"public", u"return", u"short", u"static", u"strictfp", u"super",
    u"switch", u"synchronized", u"this", u"throw", u"throws",
    u"transient", u"try", u"void", u"volatile", u"while", u"true",
    u"false", u"null"
};

static bool isValidSymbol(const StringPiece16& symbol) {
    return sJavaIdentifiers.find(symbol) == sJavaIdentifiers.end();
}

/*
 * Java symbols can not contain . or -, but those are valid in a resource name.
 * Replace those with '_'.
 */
static std::u16string transform(const StringPiece16& symbol) {
    std::u16string output = symbol.toString();
    for (char16_t& c : output) {
        if (c == u'.' || c == u'-') {
            c = u'_';
        }
    }
    return output;
}

struct GenArgs : ValueVisitorArgs {
    GenArgs(std::ostream* o, std::u16string* e) : out(o), entryName(e) {
    }

    std::ostream* out;
    std::u16string* entryName;
};

void JavaClassGenerator::visit(const Styleable& styleable, ValueVisitorArgs& a) {
    const StringPiece finalModifier = mOptions.useFinal ? " final" : "";
    std::ostream* out = static_cast<GenArgs&>(a).out;
    std::u16string* entryName = static_cast<GenArgs&>(a).entryName;

    // This must be sorted by resource ID.
    std::vector<std::pair<ResourceId, ResourceNameRef>> sortedAttributes;
    sortedAttributes.reserve(styleable.entries.size());
    for (const auto& attr : styleable.entries) {
        assert(attr.id.isValid() && "no ID set for Styleable entry");
        assert(attr.name.isValid() && "no name set for Styleable entry");
        sortedAttributes.emplace_back(attr.id, attr.name);
    }
    std::sort(sortedAttributes.begin(), sortedAttributes.end());

    // First we emit the array containing the IDs of each attribute.
    *out << "        "
         << "public static final int[] " << transform(*entryName) << " = {";

    const size_t attrCount = sortedAttributes.size();
    for (size_t i = 0; i < attrCount; i++) {
        if (i % kAttribsPerLine == 0) {
            *out << std::endl << "            ";
        }

        *out << sortedAttributes[i].first;
        if (i != attrCount - 1) {
            *out << ", ";
        }
    }
    *out << std::endl << "        };" << std::endl;

    // Now we emit the indices into the array.
    for (size_t i = 0; i < attrCount; i++) {
        *out << "        "
             << "public static" << finalModifier
             << " int " << transform(*entryName);

        // We may reference IDs from other packages, so prefix the entry name with
        // the package.
        const ResourceNameRef& itemName = sortedAttributes[i].second;
        if (itemName.package != mTable->getPackage()) {
            *out << "_" << transform(itemName.package);
        }
        *out << "_" << transform(itemName.entry) << " = " << i << ";" << std::endl;
    }
}

bool JavaClassGenerator::generateType(const std::u16string& package, size_t packageId,
                                      const ResourceTableType& type, std::ostream& out) {
    const StringPiece finalModifier = mOptions.useFinal ? " final" : "";

    std::u16string unmangledPackage;
    std::u16string unmangledName;
    for (const auto& entry : type.entries) {
        ResourceId id = { packageId, type.typeId, entry->entryId };
        assert(id.isValid());

        unmangledName = entry->name;
        if (NameMangler::unmangle(&unmangledName, &unmangledPackage)) {
            // The entry name was mangled, and we successfully unmangled it.
            // Check that we want to emit this symbol.
            if (package != unmangledPackage) {
                // Skip the entry if it doesn't belong to the package we're writing.
                continue;
            }
        } else {
            if (package != mTable->getPackage()) {
                // We are processing a mangled package name,
                // but this is a non-mangled resource.
                continue;
            }
        }

        if (!isValidSymbol(unmangledName)) {
            ResourceNameRef resourceName = { package, type.type, unmangledName };
            std::stringstream err;
            err << "invalid symbol name '" << resourceName << "'";
            mError = err.str();
            return false;
        }

        if (type.type == ResourceType::kStyleable) {
            assert(!entry->values.empty());
            entry->values.front().value->accept(*this, GenArgs{ &out, &unmangledName });
        } else {
            out << "        " << "public static" << finalModifier
                << " int " << transform(unmangledName) << " = " << id << ";" << std::endl;
        }
    }
    return true;
}

bool JavaClassGenerator::generate(const std::u16string& package, std::ostream& out) {
    const size_t packageId = mTable->getPackageId();

    generateHeader(out, package);

    out << "public final class R {" << std::endl;

    for (const auto& type : *mTable) {
        out << "    public static final class " << type->type << " {" << std::endl;
        if (!generateType(package, packageId, *type, out)) {
            return false;
        }
        out << "    }" << std::endl;
    }

    out << "}" << std::endl;
    return true;
}

} // namespace aapt