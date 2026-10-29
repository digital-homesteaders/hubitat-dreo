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
    "dreo-fan-light-ring.groovy": ["drivers/dreo-fan.groovy", "drivers/dreo-ceiling-fan.groovy"],
    "dreo-ceiling-fan-light.groovy": ["drivers/dreo-ceiling-fan.groovy"],
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
    List<String> builtIn = ["getDataValue", "updateDataValue", "removeDataValue", "currentValue", "getDeviceNetworkId", "getDisplayName"]
    drivers.each { String var, List<String> paths ->
        List<String> called = (app =~ /\b${var}\??\.(\w+)\s*\(/).collect { it[1] }.unique() - builtIn
        assert called, "no ${var}.x() calls found"
        paths.each { String p ->
            List<String> missing = called.findAll { !isPublic(hub.compile(new File(root, p)), it) }
            assert !missing, "${p} lacks public ${missing}"
        }
    }
}

// One release version everywhere, so the app's driver-version check means something.
check("every app and driver carries the packageManifest.json version") {
    String version = new groovy.json.JsonSlurper().parse(new File(root, "packageManifest.json")).version
    Map stamped = files.collectEntries { File f ->
        def v = (f.text =~ /static final String (?:DRIVER_)?VERSION = "([^"]+)"/)
        [(f.name): v.find() ? v.group(1) : "none"]
    }
    assert stamped.values().every { it == version }, "run scripts/release.sh: ${stamped} vs ${version}"
}

// scripts/release.sh writes both from its OWNER setting; this keeps them in step.
check("importUrl and packageManifest.json name the same files") {
    Map m = new groovy.json.JsonSlurper().parse(new File(root, "packageManifest.json"))
    Map locations = (m.apps + m.drivers).collectEntries { [(it.location.replaceFirst(/.*\/(apps|drivers)\//, '$1/')): it.location] }
    List paths = files.collect { root.toPath().relativize(it.toPath()).toString() }
    assert locations.keySet() == paths as Set, "manifest ${locations.keySet()} vs files ${paths}"
    String base = m.licenseFile.replaceFirst(/LICENSE$/, "")
    assert locations.values().every { it.startsWith(base) }, "manifest links name different repositories"
    files.each { File f ->
        def u = (f.text =~ /importUrl: "([^"]*)"/)
        assert u.find(), "${f.name} has no importUrl"
        String p = root.toPath().relativize(f.toPath()).toString()
        assert u.group(1) in ["", locations[p]], "${p}: importUrl ${u.group(1)} vs manifest ${locations[p]}"
    }
}

// HPM installs only the app and the cloud connection; users pick the device drivers they need.
check("packageManifest.json: only the app and the cloud connection are required") {
    Map m = new groovy.json.JsonSlurper().parse(new File(root, "packageManifest.json"))
    assert m.apps.every { it.required }
    assert m.drivers.findAll { it.required }*.name == ["Dreo Cloud Connection"]
    assert m.drivers.every { it.required instanceof Boolean }
}

Check.finish()
