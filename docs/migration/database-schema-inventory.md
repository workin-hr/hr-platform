# Database Schema Inventory

## Table Or Object

Record the table, view-like object, routine-associated table, or other schema
object being inventoried.

## Purpose

Describe what business or system capability the object supports.

## MySQL Features Used

Capture the MySQL-specific features involved, such as:

- auto-increment
- engine-specific behavior
- collation assumptions
- zero-date tolerance
- generated columns
- enum-like patterns
- triggers or routines coupled to the object

## Migration Risk

Describe the main translation or cutover risk. Useful categories include low,
moderate, high, and unknown, but the explanation matters more than the label.

## Evidence

Link the schema extract, query output, code reference, or discovery note that
supports the inventory entry.
