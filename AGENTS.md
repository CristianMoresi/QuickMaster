# QuickMaster project instructions

## Required local delivery

After every change to application code, resources, configuration, or packaging:

1. Run the complete automated test suite and build the application package.
2. Generate the Windows application image using the supported Java runtime.
3. Deploy the resulting build to `C:\Program Files\QuickMaster`.
4. Verify that the installed JAR matches the build, launch the installed executable, and confirm a clean startup in the application log.

Do not consider a change complete when it exists only in the source tree or under `target`.

## Commit messages

All local and GitHub commit messages must start with a bracketed type, followed by a brief summary, for example `[FIX] preserve intentional dynamics in the Leveler`. Use at most two lines.
