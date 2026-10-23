/*
 * Copies changed pages, stylesheets and scripts into the running deployment.
 *
 * GlassFish serves its own copy of the application, under
 * <domain>/applications/tripmate/tripmate-web_war, and nothing in the working
 * tree reaches the browser until that copy is replaced. A full deploy rebuilds
 * the EAR to do it, which takes a minute; this takes no time at all.
 *
 * What it does not cover: anything in a .java file. Those are class files
 * inside the EAR, and no amount of copying a page will pick one up.
 *
 * Run through scripts/live.sh, which is where the paths come from.
 */
import { watch, copyFileSync, mkdirSync, statSync } from "node:fs";
import { dirname, relative, join, extname, resolve } from "node:path";

const [source, deployed, mode] = process.argv.slice(2);
const WATCHED = new Set([".xhtml", ".css", ".js"]);

function copy(file) {
    if (!WATCHED.has(extname(file))) {
        return false;
    }
    const relativePath = relative(source, file);
    if (relativePath.startsWith("..")) {
        return false;
    }
    const destination = join(deployed, relativePath);
    try {
        mkdirSync(dirname(destination), { recursive: true });
        copyFileSync(file, destination);
        return relativePath;
    } catch (error) {
        console.error(`  failed: ${relativePath} (${error.message})`);
        return false;
    }
}

function stamp() {
    return new Date().toLocaleTimeString("en-GB", { hour12: false });
}

if (mode === "--watch") {
    console.log(`Watching ${source}`);
    console.log("Save a page, stylesheet or script and it lands in the running app.");
    console.log("Reload the browser to see it. Ctrl+C to stop.\n");

    watch(source, { recursive: true }, (event, filename) => {
        if (!filename) {
            return;
        }
        const file = resolve(join(source, filename));
        let isFile = true;
        try {
            isFile = statSync(file).isFile();
        } catch (missing) {
            /* A delete, or a file that was renamed away between the event and
               this call. Nothing to copy. */
            return;
        }
        if (!isFile) {
            return;
        }
        const copied = copy(file);
        if (copied) {
            console.log(`${stamp()}  copied  ${copied}`);
        }
    });
} else {
    /* One pass over the tree, for the files newer than what is deployed. */
    const { readdirSync } = await import("node:fs");
    let copied = 0;
    const walk = (directory) => {
        for (const entry of readdirSync(directory, { withFileTypes: true })) {
            const path = join(directory, entry.name);
            if (entry.isDirectory()) {
                walk(path);
            } else if (WATCHED.has(extname(entry.name))) {
                const target = join(deployed, relative(source, path));
                let older = true;
                try {
                    older = statSync(path).mtimeMs > statSync(target).mtimeMs;
                } catch (absent) {
                    older = true;
                }
                if (older && copy(path)) {
                    console.log(`  copied  ${relative(source, path)}`);
                    copied += 1;
                }
            }
        }
    };
    walk(source);
    console.log(copied === 0
        ? "Nothing newer than the deployment."
        : `\n${copied} file(s) copied. Reload the page, Ctrl+Shift+R if it looks stale.`);
}
