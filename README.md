# Patient Diagnosis CSV Filter

This desktop application filters patient records using two diagnosis-code lists.
It writes one output row for each patient who has at least one diagnosis matching
the first list **and** at least one diagnosis matching the second list. The
diagnoses may be on different input rows for the same patient.

## Run the application

The project folder contains the executable `Crawler.jar`. Open it with a
double-click, or run it from a terminal:

```text
java -jar Crawler.jar
```

A Java runtime must be installed. In the window, select:

1. The patient data CSV.
2. The first diagnosis-code list CSV.
3. The second diagnosis-code list CSV.
4. The output CSV path.
  - The application suggests `filtered_patients.csv` beside the patient data file.
  - If the output file already exists, the application asks before replacing it.


## Patient data CSV format

The patient data file must have a header row and at least four columns:

| -1- | -2- | -3- | -4- |
| 1 | Patient identifier | `12345` |
| 2 | Any other patient or encounter field | `encounter-1` |
| 3 | Any other patient or encounter field | `2026-01-15` |
| 4 | One diagnosis code for this row | `F20.9` |

Additional columns are allowed. A patient may have multiple rows, **with one diagnosis per row**. The program groups rows by the identifier in column 1.

Patient identifiers are trimmed at the edges; otherwise they are compared exactly, including case and leading zeroes. 

**Do not use a spreadsheet application that converts identifiers into numbers, removes leading zeroes, or displays them in scientific notation.**

Example:

```csv
patient_id,encounter_id,date,diagnosis_code
00123,E1,2026-01-15,F20.9
00123,E2,2026-02-10,F10.150
00456,E3,2026-03-01,F20.9
```

## Diagnosis-code list CSV format

Each list file must have a header row. The first column contains diagnosis codes or patterns; additional columns are ignored. The project includes:

- `psychosis_diagnosis_codes.csv`
- `drug_use_related_codes.csv`

Both files use this format:

```csv
code_pattern,code_system,description
"F20.9","ICD-10-CM","Schizophrenia, unspecified"
"295*","ICD-9-CM","Schizophrenic disorders and subcodes"
```

Code matching ignores letter case and decimal points. In a code pattern,
`*` matches zero or more characters and `?` matches exactly one character.
For example, `295*` matches `295`, `295.9`, and `29590`. Patterns should be
used carefully: a broad pattern can match more codes than intended.

## Patient selection and output

A patient is included when the patient's diagnoses, across all input rows,
match at least one entry from each selected code list. All diagnosis codes
found for that patient in input column 4 are included in the output, not only
the codes that caused the patient to match. Duplicate diagnoses are included
once, in the order first seen in the input.

The output CSV has one row per qualifying patient and these columns:

| Column | Contents |
| --- | --- |
| 1 | Patient identifier; the heading is copied from column 1 of the input header |
| 2 | `has_substance_related_psychosis_dx`, set to `true` if a specific substance-induced psychosis diagnosis code was found; otherwise `false` |
| 3 | `diagnosis_codes`, containing that patient's distinct input diagnosis codes in one CSV field, separated by commas |

The diagnosis-code field may contain commas, so it can appear quoted in the
CSV. Quoting is normal CSV formatting; the commas inside the quoted field are
part of the diagnosis-code list, not extra output columns.

## Notes

- All three input CSVs are expected to have headers. Header rows are not
  searched for diagnosis codes.
- The two selected code lists are combined with **AND** at the patient level:
  a match in only one list is not sufficient.
- The substance-related psychosis flag is independent of list matching. It
  requires a diagnosis code from a substance-induced psychosis family; having
  a separate psychosis code and substance-use code does not by itself set the
  flag.
- The bundled ICD-10-CM entries are based on the 2026 code set. ICD-9-CM family
  entries use patterns.
- The output path must be different from all input paths.



*This code and all included material were created by Theresa Smith, including use of AI tools, for the use of the GPF Foundation.*