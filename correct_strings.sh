#!/bin/bash

set -e

mydir="$(dirname "$(realpath "$0")")"
source "$mydir/merge_helpers.sh"

pushd "$mydir" > /dev/null

mydir="."

# Haven: Element -> Haven, but restore Element where it makes sense (Element Call, Element Web/iOS/Desktop,
# Element Pro, ...). Also migrate strings that still carry SchildiChat's branding from earlier merges.
# "SchildiChat Legacy" stays: it names the old SchildiChat app that sessions can be imported from.
find "$mydir" -not -path "./schildi/**" \
    \( -name translations.xml -o -name localazy.xml \) -exec \
    sed -i 's|SchildiChat Next|Haven|g;
            s|SchildiChat Legacy|@@SC_LEGACY@@|g;
            s|SchildiChat|Haven|g;
            s|Element|Haven|g;
            s|Haven X|Haven|g;
            s/Haven \(Web\|iOS\|Desktop\)/Element \1/g;
            s|Haven Matrix Services|Element Matrix Services|g;
            s|\(name="screen_onboarding_welcome_title".*\)Haven|\1Element|g;
            s|\(name=".*_call_.*".*\)Haven|\1Element|g;
            s|\(name=".*element_dot_io.*".*\)Haven|\1Element|g;
            s|\(name=".*screen_notification_settings_sound_element_.*".*\)Haven|\1Element|g;
            s/Haven\( \|-\)Pro/Element\1Pro/g;
            s/Havenul/Elementul/g;
            s/Haven Classic/SchildiChat Legacy/g;
            s/Haven\( \|-\)Call/Element\1Call/g;
            s|@@SC_LEGACY@@|SchildiChat Legacy|g' '{}' \;

git --no-pager diff -- '*/translations.xml' '*/localazy.xml'

popd > /dev/null
