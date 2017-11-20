; RUN: llvm-as < %s | rs2spirv -spirv-text -o %t
; RUN: FileCheck < %t %s

; TODO: Complete the test.

target datalayout = "e-m:e-i64:64-i128:128-n32:64-S128"
target triple = "aarch64-none-linux-gnueabi"

; Function Attrs: norecurse nounwind readnone
; CHECK: Name [[FooIdx:[0-9]+]] "times"
define i32 @times(i32 %x) #0 {
entry:
  %mul = shl i32 %x, 1
  ret i32 %mul
}

attributes #0 = { norecurse nounwind readnone "disable-tail-calls"="false" "less-precise-fpmad"="false" "no-frame-pointer-elim"="false" "no-infs-fp-math"="false" "no-nans-fp-math"="false" "stack-protector-buffer-size"="0" "stackrealign" "unsafe-fp-math"="false" "use-soft-float"="false" }

!llvm.ident = !{!0}
!\23pragma = !{!1, !2, !3}
!\23rs_export_foreach_name = !{!4, !5}
!\23rs_export_foreach = !{!6, !7}

!0 = !{!"Android clang version 3.8.256229  (based on LLVM 3.8.256229)"}
!1 = !{!"version", !"1"}
!2 = !{!"java_package_name", !"rs2spirv"}
!3 = !{!"rs_fp_relaxed", !""}
!4 = !{!"root"}
!5 = !{!"times"}
!6 = !{!"0"}
!7 = !{!"42"}
