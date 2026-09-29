# QuickMaster project instructions

## Canonical workspace

Work only in `E:\Code\Projects\JA-DAW\QuickMaster`, on `main`. This is the
current source tree, including uncommitted work. Do not recreate sibling
Integration, Rapid, recovery or test checkouts, or auxiliary branches, unless
the user explicitly changes this instruction.

Historical work is preserved under `.archive/`; it is not an active project.
Licensed local signals are under `test-data/official/`. Keep both out of Git and
release packages. Old absolute paths in evidence are provenance, not current
workspace instructions. See `WORKSPACE.md` for layout and recovery information.

## Required local delivery

After every change to application code, resources, configuration, or packaging:

1. Run the complete automated test suite and build the application package.
2. Generate the Windows application image using the supported Java runtime.
3. Deploy the resulting build to `C:\Program Files\QuickMaster`.
4. Verify that the installed JAR matches the build, launch the installed executable, and confirm a clean startup in the application log.

Do not consider a change complete when it exists only in the source tree or under `target`.

## Commit messages

All local and GitHub commit messages must start with a bracketed type such as `[FIX]` or `[DOC]`, followed by a brief summary. Use at most two lines.

Write commit messages, tag annotations, changelog entries and GitHub release text in English.

Use the repository's configured Cristian Moresi identity as the sole author and committer. Do not add assistant, bot, or AI contributor credits, co-author trailers, or generated-by attribution.

## Product acceptance before delivery or release

A passing automated suite is necessary but not sufficient. Reproduce each reported
audio failure with the actual user-supplied track and the packaged application.
For the Leveler, verify a positive, measurable correction on suitable real musical
sections as well as protection of intentional dynamics. An unchanged render or a
`Ready` label is not evidence that leveling works. Record the applied gain and
changed samples, investigate empty correction plans, and do not release with an
unresolved reported regression. Keep source audio untouched and do not distribute it.
