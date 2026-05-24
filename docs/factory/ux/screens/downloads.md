# Downloads

## Purpose

Expose build artifacts that are useful outside the dashboard, especially APKs.

## Layout

- Page header: `Downloads`.
- Subtitle describing artifact type.
- List of artifact cards.
- Each card: icon, artifact name, description, build date, size, download
  button.

## Data

- Artifact name.
- Description.
- Build timestamp.
- Size.
- Download URL.
- Optional checksum/version.

## Actions

- Download artifact.
- Refresh.

## States

- No artifacts.
- Download unavailable.

## Notes

Keep this screen simple. It is not a build browser; only show artifacts the user
is expected to download.
