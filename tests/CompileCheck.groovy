/**
 * Compile every app and driver against the hub stubs and run its top-level
 * code (definition, metadata, preferences). Then check that each method a
 * driver calls on its parent exists there and is not private, since Hubitat
 * refuses calls to private methods across objects.
 */

import java.lang.reflect.Modifier
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Hub hub = new Hub(root)
List<File> files = (new File(root, "apps").listFiles() + new File(root, "drivers").listFiles())
    .findAll { it.name.endsWith(".groovy") }.sort { it.path }

files.each { File f ->
    check("compiles and loads: ${root.toPath().relativize(f.toPath())}") {
        HubitatScript s = hub.instance(f)
        assert s.callsNamed("definition"), "no definition(...)"
    }
}

// A script method named like a metadata/page DSL word (e.g. a private
// `command(...)` helper next to `command "setMode"`) may shadow the DSL.
List<String> dslWords = ["definition", "capability", "attribute", "command", "fingerprint", "input",
    "section", "page", "paragraph", "href", "label", "mode", "app"]
files.each { File f ->
    check("no method shadows a definition word: ${f.name}") {
        Class c = hub.compile(f)
        List<String> clash = c.declaredMethods*.name.unique().findAll { it in dslWords }
        assert !clash
    }
}

// Which code each driver's `parent` is.
Map<String, List<String>> parents = [
    "dreo-fan.groovy": ["apps/dreo-connect.groovy"],
    "dreo-ceiling-fan.groovy": ["apps/dreo-connect.groovy"],
    "dreo-humidifier.groovy": ["apps/dreo-connect.groovy"],
    "dreo-cloud-connection.groovy": ["apps/dreo-connect.groovy"],
    "dreo-light-ring.groovy": ["drivers/dreo-fan.groovy", "drivers/dreo-ceiling-fan.groovy"],
    "dreo-ceiling-light.groovy": ["drivers/dreo-ceiling-fan.groovy"],
]

boolean isPublic(Class c, String name) {
    c.methods.any { it.name == name && !Modifier.isPrivate(it.modifiers) }
}

new File(root, "drivers").listFiles().findAll { it.name.endsWith(".groovy") }.each { File f ->
    List<String> called = (f.text =~ /parent\??\.(\w+)\s*\(/).collect { it[1] }.unique()
    check("parent methods called by ${f.name} exist and are public") {
        assert parents[f.name], "no parent listed for ${f.name}"
        parents[f.name].each { String p ->
            Class c = hub.compile(new File(root, p))
            List<String> missing = called.findAll { !isPublic(c, it) }
            assert !missing, "${p} lacks public ${missing}"
        }
    }
}

// Methods the app calls on child devices, by variable name in the app.
check("child device methods called by the app exist and are public") {
    String app = new File(root, "apps/dreo-connect.groovy").text
    Map<String, List<String>> drivers = [
        cd: ["drivers/dreo-fan.groovy", "drivers/dreo-ceiling-fan.groovy", "drivers/dreo-humidifier.groovy"],
        conn: ["drivers/dreo-cloud-connection.groovy"],
    ]
    // Hubitat's own device methods, not the driver's.
    List<String> builtIn = ["getDataValue", "updateDataValue", "currentValue", "getDeviceNetworkId", "getDisplayName"]
    drivers.each { String var, List<String> paths ->
        List<String> called = (app =~ /\b${var}\??\.(\w+)\s*\(/).collect { it[1] }.unique() - builtIn
        assert called, "no ${var}.x() calls found"
        paths.each { String p ->
            List<String> missing = called.findAll { !isPublic(hub.compile(new File(root, p)), it) }
            assert !missing, "${p} lacks public ${missing}"
        }
    }
}

Check.finish()
