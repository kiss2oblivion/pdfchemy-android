param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo '.security-runtime/word-exports' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$sourcePath = Join-Path $repo 'reports/release-certification-2026-10-07/corpus/word-source.json'
$cases = [IO.File]::ReadAllText($sourcePath, [Text.Encoding]::UTF8) | ConvertFrom-Json
$word = $null
$receipt = @()
try {
    # Separate hidden application instance; never attach to or edit the user's documents.
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    foreach ($case in $cases) {
        $document = $word.Documents.Add()
        try {
            $document.PageSetup.PageWidth = 612
            $document.PageSetup.PageHeight = 792
            $document.PageSetup.TopMargin = 72
            $document.PageSetup.BottomMargin = 72
            $document.PageSetup.LeftMargin = 72
            $document.PageSetup.RightMargin = 72
            $document.RemovePersonalInformation = $true
            $body = $case.title + "`r" + $case.heading + "`r" + $case.body + "`r"
            if ($case.url) { $body += $case.url + "`r" }
            $document.Content.Text = $body
            $document.Content.Font.Name = 'Calibri'
            $document.Content.Font.Size = 11
            $document.Content.Font.Color = 0
            $document.Paragraphs.Item(1).Range.Style = -63 # Word Title
            $document.Paragraphs.Item(1).Range.Font.Color = 0
            $document.Paragraphs.Item(1).Range.Font.Size = 16
            $document.Paragraphs.Item(2).Range.Style = -2 # Word Heading 1
            $document.Paragraphs.Item(2).Range.Font.Color = 0
            if ($case.url) {
                $anchor = $document.Content.Duplicate
                if (-not $anchor.Find.Execute($case.url)) { throw 'Hyperlink anchor not found' }
                $document.Hyperlinks.Add($anchor, $case.url) | Out-Null
            }
            $output = Join-Path $OutputDirectory $case.file
            # Native Word PDF export, document properties, heading bookmarks and tagged text.
            $document.ExportAsFixedFormat($output, 17, $false, 0, 0, 1, 1, 0, $true, $true, 1, $true, $true, $false)
            $receipt += [pscustomobject]@{
                file = $case.file; exporter = 'Microsoft Word'; version = $word.Version
                build = $word.Build; generated_utc = [DateTime]::UtcNow.ToString('o')
                source_sha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
                sha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        } finally { $document.Close(0); [Runtime.InteropServices.Marshal]::ReleaseComObject($document) | Out-Null }
    }
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'word-export-receipt.json'),
        ($receipt | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
} finally {
    if ($word) { $word.Quit(); [Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null }
}
