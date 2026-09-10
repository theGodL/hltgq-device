$files = Get-ChildItem -Recurse 'D:\Java\code\hltgq\hltgq-device\src\main\java' -Filter '*.java'
foreach ($f in $files) {
    $m = Select-String -Path $f.FullName -Pattern 'collectVideoChannels|class VideoChannel|getDevicecode|devicecode|epjutj|t_auto_hltgq_5nw74|VideoChannel'
    foreach ($x in $m) {
        Write-Output ("{0}:{1}: {2}" -f $f.Name, $x.LineNumber, $x.Line.Trim())
    }
}
